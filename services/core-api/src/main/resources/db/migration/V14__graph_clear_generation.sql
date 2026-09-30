CREATE TABLE graph_project_state (
    project_id UUID PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    generation BIGINT NOT NULL DEFAULT 1,
    resetting BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_graph_project_generation CHECK (generation > 0)
);
INSERT INTO graph_project_state(project_id)
SELECT id FROM project
ON CONFLICT DO NOTHING;
ALTER TABLE graph_canonical_entity
    ADD COLUMN generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE graph_canonical_entity
    ADD CONSTRAINT ck_graph_canonical_generation CHECK (generation > 0);
CREATE INDEX idx_graph_canonical_generation
    ON graph_canonical_entity(project_id,generation,id);
