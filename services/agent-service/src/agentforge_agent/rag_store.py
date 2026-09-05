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
from .schemas import RagSource


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

    def synchronize(self, project_id: UUID, sources: list[RagSource], embedder: EmbeddingProvider) -> None:
        try:
            with psycopg.connect(self.dsn) as connection:
                register_vector(connection)
                with connection.cursor() as cursor:
                    cursor.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", (str(project_id),))
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
        except (psycopg.Error, ValueError) as exception:
            raise RagDependencyError("RAG index database is unavailable.") from exception

    def search(
        self,
        project_id: UUID,
        query: str,
        query_embedding: list[float],
        candidate_k: int,
        min_similarity: float = 0.2,
    ) -> tuple[dict[str, StoredChunk], list[str], list[str]]:
        try:
            with psycopg.connect(self.dsn) as connection:
                register_vector(connection)
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT id, project_id, source_type, source_id, source_version,
                               chunk_index, title, content
                        FROM rag_chunk
                        WHERE project_id = %s
                        ORDER BY source_type, source_id, chunk_index
                        """,
                        (project_id,),
                    )
                    all_rows = cursor.fetchall()

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
        except psycopg.Error as exception:
            raise RagDependencyError("RAG index database is unavailable.") from exception

        stored_chunks = [_stored_chunk(row) for row in all_rows]
        chunks = {chunk.id: chunk for chunk in stored_chunks}
        lexical_ids = bm25_rank(
            query,
            [RankableChunk(chunk.id, chunk.content) for chunk in chunks.values()],
            candidate_k,
        )
        return chunks, vector_ids, lexical_ids


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
