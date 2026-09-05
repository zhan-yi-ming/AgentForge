CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag_chunk (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id UUID NOT NULL,
    source_version BIGINT NOT NULL,
    chunk_index INTEGER NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(384) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_rag_chunk_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_rag_chunk_source_type CHECK (source_type IN ('WIKI', 'TASK')),
    CONSTRAINT ck_rag_chunk_source_version_non_negative CHECK (source_version >= 0),
    CONSTRAINT ck_rag_chunk_index_non_negative CHECK (chunk_index >= 0),
    CONSTRAINT ck_rag_chunk_content_not_blank CHECK (length(btrim(content)) > 0),
    CONSTRAINT uk_rag_chunk_source_version_index UNIQUE (source_type, source_id, source_version, chunk_index)
);

CREATE INDEX idx_rag_chunk_project_source
    ON rag_chunk (project_id, source_type, source_id);

CREATE INDEX idx_rag_chunk_embedding_cosine
    ON rag_chunk USING hnsw (embedding vector_cosine_ops);
