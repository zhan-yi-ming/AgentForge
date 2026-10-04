from pathlib import Path
from uuid import uuid4

import psycopg
from testcontainers.community.postgres import PostgresContainer

from agentforge_agent.embeddings import HashEmbeddingProvider
from agentforge_agent.rag_store import RagStore
from agentforge_agent.schemas import RagSource


def test_pgvector_store_replaces_versions_removes_deleted_sources_and_isolates_projects() -> None:
    migration_directory = (
        Path(__file__).resolve().parents[2]
        / "core-api"
        / "src"
        / "main"
        / "resources"
        / "db"
        / "migration"
    )
    migrations = [
        (migration_directory / filename).read_text(encoding="utf-8")
        for filename in (
            "V1__create_users_and_projects.sql",
            "V2__add_security_wiki_and_tasks.sql",
            "V3__add_rag_chunks.sql",
            "V17__rag_snapshot_generation.sql",
        )
    ]

    with PostgresContainer("pgvector/pgvector:pg17") as postgres:
        dsn = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql")
        with psycopg.connect(dsn) as connection:
            for migration in migrations:
                connection.execute(migration)

        store = RagStore(dsn)
        embedder = HashEmbeddingProvider(384)
        project_id = uuid4()
        other_project_id = uuid4()
        owner_id = uuid4()
        wiki_id = uuid4()
        task_id = uuid4()
        other_id = uuid4()

        with psycopg.connect(dsn) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO app_user (id, email, display_name, role, created_at, updated_at)
                    VALUES (%s, 'rag-test@example.com', 'RAG Test', 'USER', now(), now())
                    """,
                    (owner_id,),
                )
                cursor.executemany(
                    """
                    INSERT INTO project (id, owner_id, name, created_at, updated_at)
                    VALUES (%s, %s, %s, now(), now())
                    """,
                    [
                        (project_id, owner_id, "RAG Project"),
                        (other_project_id, owner_id, "Other Project"),
                    ],
                )

        wiki = RagSource(
            sourceType="WIKI", sourceId=wiki_id, version=0,
            title="Authentication", content="Java owns authentication and authorization.",
        )
        task = RagSource(
            sourceType="TASK", sourceId=task_id, version=0,
            title="Ship RAG", content="Implement retrieval fusion with RRF.",
        )
        other = RagSource(
            sourceType="WIKI", sourceId=other_id, version=0,
            title="Secret Project", content="confidential zebra material",
        )
        assert store.synchronize(project_id, 10, [wiki, task], embedder)
        assert store.synchronize(other_project_id, 11, [other], embedder)

        chunks, vector_ids, lexical_ids = store.search(
            project_id, 10, "authentication", embedder.embed(["authentication"])[0], 10,
        )
        assert lexical_ids
        assert vector_ids
        assert chunks[lexical_ids[0]].source_id == wiki_id
        assert chunks[vector_ids[0]].source_id == wiki_id
        assert all(chunk.project_id == project_id for chunk in chunks.values())
        assert all(chunks[chunk_id].source_id != other_id for chunk_id in vector_ids)

        updated_wiki = RagSource(
            sourceType="WIKI", sourceId=wiki_id, version=1,
            title="Authentication", content="Spring Security validates JWT tokens.",
        )
        assert store.synchronize(project_id, 12, [updated_wiki], embedder)

        with psycopg.connect(dsn) as connection:
            rows = connection.execute(
                "SELECT source_id, source_version FROM rag_chunk WHERE project_id = %s",
                (project_id,),
            ).fetchall()
        assert rows
        assert {row[0] for row in rows} == {wiki_id}
        assert {row[1] for row in rows} == {1}

        late_source = RagSource(
            sourceType="TASK", sourceId=task_id, version=1,
            title="Late task", content="This source belongs only to the stale snapshot.",
        )
        assert not store.synchronize(project_id, 11, [wiki, late_source], embedder)

        with psycopg.connect(dsn) as connection:
            rows = connection.execute(
                "SELECT source_id, source_version FROM rag_chunk WHERE project_id = %s",
                (project_id,),
            ).fetchall()
            snapshot_version = connection.execute(
                "SELECT snapshot_version FROM rag_project_snapshot WHERE project_id = %s",
                (project_id,),
            ).fetchone()[0]
        assert rows
        assert {row[0] for row in rows} == {wiki_id}
        assert {row[1] for row in rows} == {1}
        assert snapshot_version == 12

        stale_chunks, stale_vector, stale_lexical = store.search(
            project_id, 11, "authentication", embedder.embed(["authentication"])[0], 10,
        )
        assert stale_chunks == {}
        assert stale_vector == []
        assert stale_lexical == []

        current_chunks, current_vector, current_lexical = store.search(
            project_id, 12, "authentication", embedder.embed(["authentication"])[0], 10,
        )
        assert current_chunks
        assert current_vector
        assert current_lexical
