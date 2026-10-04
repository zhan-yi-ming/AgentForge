from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, NAMESPACE_URL, uuid5

import psycopg
from pgvector import Vector
from pgvector.psycopg import register_vector

from .chunking import Chunk, chunk_source
from .embeddings import EmbeddingProvider
from .errors import RagDependencyError
from .ranking import RankableChunk, bm25_rank
from .schemas import GraphMatch, RagSource


@dataclass(frozen=True)
class StoredChunk:
    id: str
    project_id: UUID
    source_type: str
    source_id: UUID
    source_version: int
    chunk_index: int
    title: str
    content: str


class RagStore:
    def __init__(self, dsn: str) -> None:
        self.dsn = dsn

    def snapshot_version(self, project_id: UUID) -> int | None:
        try:
            with psycopg.connect(self.dsn) as connection:
                row = connection.execute(
                    "SELECT snapshot_version FROM rag_project_snapshot WHERE project_id = %s",
                    (project_id,),
                ).fetchone()
                return None if row is None else int(row[0])
        except psycopg.Error as exception:
            raise RagDependencyError("RAG index database is unavailable.") from exception

    def synchronize(
        self,
        project_id: UUID,
        snapshot_version: int,
        sources: list[RagSource],
        embedder: EmbeddingProvider,
    ) -> bool:
        try:
            if snapshot_version < 0:
                raise ValueError("snapshot_version must be non-negative")
            with psycopg.connect(self.dsn) as connection:
                register_vector(connection)
                with connection.cursor() as cursor:
                    cursor.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", (str(project_id),))
                    cursor.execute(
                        "SELECT snapshot_version FROM rag_project_snapshot WHERE project_id = %s",
                        (project_id,),
                    )
                    applied = cursor.fetchone()
                    if applied is not None and snapshot_version < int(applied[0]):
                        return False
                    if applied is not None and snapshot_version == int(applied[0]):
                        return True
                    cursor.execute(
                        """
                        SELECT source_type, source_id, max(source_version)
                        FROM rag_chunk
                        WHERE project_id = %s
                        GROUP BY source_type, source_id
                        """,
                        (project_id,),
                    )
                    existing = {(row[0], row[1]): row[2] for row in cursor.fetchall()}
                    current = {(source.source_type, source.source_id): source for source in sources}

                    for source_key in existing.keys() - current.keys():
                        cursor.execute(
                            "DELETE FROM rag_chunk WHERE project_id = %s AND source_type = %s AND source_id = %s",
                            (project_id, source_key[0], source_key[1]),
                        )

                    for source_key, source in current.items():
                        if existing.get(source_key) == source.version:
                            continue
                        chunks = chunk_source(project_id, source)
                        embeddings = embedder.embed([chunk.content for chunk in chunks])
                        cursor.execute(
                            "DELETE FROM rag_chunk WHERE project_id = %s AND source_type = %s AND source_id = %s",
                            (project_id, source.source_type, source.source_id),
                        )
                        for chunk, embedding in zip(chunks, embeddings, strict=True):
                            cursor.execute(
                                """
                                INSERT INTO rag_chunk (
                                    id, project_id, source_type, source_id, source_version,
                                    chunk_index, title, content, embedding, created_at
                                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                                ON CONFLICT (source_type, source_id, source_version, chunk_index)
                                DO UPDATE SET
                                    project_id = EXCLUDED.project_id,
                                    title = EXCLUDED.title,
                                    content = EXCLUDED.content,
                                    embedding = EXCLUDED.embedding,
                                    created_at = EXCLUDED.created_at
                                """,
                                (
                                    _chunk_id(chunk),
                                    chunk.project_id,
                                    chunk.source_type,
                                    chunk.source_id,
                                    chunk.source_version,
                                    chunk.chunk_index,
                                    chunk.title,
                                    chunk.content,
                                    Vector(embedding),
                                    datetime.now(timezone.utc),
                                ),
                            )
                    cursor.execute(
                        """
                        INSERT INTO rag_project_snapshot (project_id, snapshot_version, updated_at)
                        VALUES (%s, %s, now())
                        ON CONFLICT (project_id) DO UPDATE SET
                            snapshot_version = EXCLUDED.snapshot_version,
                            updated_at = EXCLUDED.updated_at
                        """,
                        (project_id, snapshot_version),
                    )
            return True
        except (psycopg.Error, ValueError) as exception:
            raise RagDependencyError("RAG index database is unavailable.") from exception

    def search(
        self,
        project_id: UUID,
        snapshot_version: int,
        query: str,
        query_embedding: list[float],
        candidate_k: int,
        min_similarity: float = 0.2,
    ) -> tuple[dict[str, StoredChunk], list[str], list[str]]:
        try:
            with psycopg.connect(self.dsn) as connection:
                connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                register_vector(connection)
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT snapshot_version FROM rag_project_snapshot WHERE project_id = %s",
                        (project_id,),
                    )
                    applied = cursor.fetchone()
                    if applied is None or int(applied[0]) != snapshot_version:
                        return {}, [], []
                    cursor.execute(
                        """
                        SELECT id
                        FROM rag_chunk
                        WHERE project_id = %s AND 1 - (embedding <=> %s) >= %s
                        ORDER BY embedding <=> %s, id
                        LIMIT %s
                        """,
                        (
                            project_id,
                            Vector(query_embedding),
                            min_similarity,
                            Vector(query_embedding),
                            candidate_k,
                        ),
                    )
                    vector_ids = [str(row[0]) for row in cursor.fetchall()]
                    cursor.execute(
                        """
                        SELECT id
                        FROM rag_chunk
                        WHERE project_id = %s
                          AND search_document @@ plainto_tsquery('simple', %s)
                        ORDER BY ts_rank_cd(
                            search_document,
                            plainto_tsquery('simple', %s)
                        ) DESC, id
                        LIMIT %s
                        """,
                        (project_id, query, query, candidate_k),
                    )
                    lexical_candidate_ids = [str(row[0]) for row in cursor.fetchall()]
                    candidate_ids = list(dict.fromkeys([*vector_ids, *lexical_candidate_ids]))
                    if not candidate_ids:
                        return {}, vector_ids, []
                    cursor.execute(
                        """
                        SELECT id, project_id, source_type, source_id, source_version,
                               chunk_index, title, content
                        FROM rag_chunk
                        WHERE project_id = %s AND id = ANY(%s::uuid[])
                        """,
                        (project_id, candidate_ids),
                    )
                    candidate_rows = cursor.fetchall()
        except psycopg.Error as exception:
            raise RagDependencyError("RAG index database is unavailable.") from exception

        stored_chunks = [_stored_chunk(row) for row in candidate_rows]
        chunks = {chunk.id: chunk for chunk in stored_chunks}
        lexical_ids = bm25_rank(
            query,
            [
                RankableChunk(chunk_id, chunks[chunk_id].content)
                for chunk_id in lexical_candidate_ids
                if chunk_id in chunks
            ],
            candidate_k,
        )
        return chunks, vector_ids, lexical_ids

    def load_graph_evidence(
        self,
        project_id: UUID,
        snapshot_version: int,
        matches: list[GraphMatch],
    ) -> dict[tuple[str, UUID, int], StoredChunk]:
        result: dict[tuple[str, UUID, int], StoredChunk] = {}
        try:
            with psycopg.connect(self.dsn) as connection:
                connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT snapshot_version FROM rag_project_snapshot WHERE project_id = %s",
                        (project_id,),
                    )
                    applied = cursor.fetchone()
                    if applied is None or int(applied[0]) != snapshot_version:
                        return {}
                    for match in matches[:40]:
                        evidence = match.evidence
                        cursor.execute(
                            """
                            SELECT id, project_id, source_type, source_id, source_version,
                                   chunk_index, title, content
                            FROM rag_chunk
                            WHERE project_id = %s
                              AND source_type = %s
                              AND source_id = %s
                              AND source_version = %s
                              AND strpos(content, %s) > 0
                            ORDER BY chunk_index
                            LIMIT 1
                            """,
                            (
                                project_id,
                                evidence.source_type,
                                evidence.source_id,
                                evidence.source_version,
                                evidence.excerpt,
                            ),
                        )
                        row = cursor.fetchone()
                        if row is not None:
                            result[(
                                evidence.source_type,
                                evidence.source_id,
                                evidence.source_version,
                            )] = _stored_chunk(row)
        except psycopg.Error as exception:
            raise RagDependencyError("RAG index database is unavailable.") from exception
        return result


def _chunk_id(chunk: Chunk) -> UUID:
    identity = (
        f"{chunk.project_id}:{chunk.source_type}:{chunk.source_id}:"
        f"{chunk.source_version}:{chunk.chunk_index}"
    )
    return uuid5(NAMESPACE_URL, identity)


def _stored_chunk(row: tuple[object, ...]) -> StoredChunk:
    return StoredChunk(
        id=str(row[0]),
        project_id=row[1],
        source_type=str(row[2]),
        source_id=row[3],
        source_version=int(row[4]),
        chunk_index=int(row[5]),
        title=str(row[6]),
        content=str(row[7]),
    )
