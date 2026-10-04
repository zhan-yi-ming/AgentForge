ALTER TABLE rag_chunk
    ADD COLUMN search_document TSVECTOR
    GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, ''))
    ) STORED;

CREATE INDEX idx_rag_chunk_search_document
    ON rag_chunk USING GIN (search_document);
