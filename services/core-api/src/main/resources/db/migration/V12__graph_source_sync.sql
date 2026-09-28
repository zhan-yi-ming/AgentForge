CREATE TABLE graph_source_sync (
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    source_type VARCHAR(10) NOT NULL,
    source_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, source_type, source_id),
    CONSTRAINT ck_graph_source_sync_type CHECK (source_type IN ('WIKI', 'TASK'))
);
CREATE INDEX idx_graph_source_sync_due ON graph_source_sync (next_attempt_at, updated_at);
INSERT INTO graph_source_sync (project_id, source_type, source_id)
SELECT project_id, 'WIKI', id FROM wiki_page;
INSERT INTO graph_source_sync (project_id, source_type, source_id)
SELECT project_id, 'TASK', id FROM task_item;
