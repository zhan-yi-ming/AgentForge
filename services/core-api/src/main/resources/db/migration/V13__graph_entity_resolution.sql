CREATE TABLE graph_canonical_entity (
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    entity_type VARCHAR(16) NOT NULL,
    canonical_name VARCHAR(200) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    anchor_source_type VARCHAR(10) NOT NULL,
    anchor_source_id UUID NOT NULL,
    anchor_source_version BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, id),
    CONSTRAINT ck_graph_canonical_type CHECK (entity_type IN ('SERVICE','API','ISSUE')),
    CONSTRAINT ck_graph_canonical_name CHECK (length(btrim(canonical_name)) > 0),
    CONSTRAINT ck_graph_canonical_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_graph_canonical_anchor_source CHECK (anchor_source_type IN ('WIKI','TASK')),
    CONSTRAINT ck_graph_canonical_anchor_version CHECK (anchor_source_version >= 0),
    CONSTRAINT ck_graph_canonical_version CHECK (version > 0)
);
CREATE TABLE graph_resolution_member (
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    entity_id UUID NOT NULL,
    canonical_id UUID,
    source_type VARCHAR(10) NOT NULL,
    source_id UUID NOT NULL,
    source_version BIGINT NOT NULL,
    canonical_source_version BIGINT NOT NULL,
    aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    confidence DOUBLE PRECISION NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    confirmed_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, entity_id),
    CONSTRAINT fk_graph_resolution_canonical FOREIGN KEY (project_id, canonical_id)
        REFERENCES graph_canonical_entity(project_id,id),
    CONSTRAINT ck_graph_resolution_status CHECK (status IN ('CONFIRMED','REVERTED')),
    CONSTRAINT ck_graph_resolution_canonical_status CHECK
        ((status='CONFIRMED' AND canonical_id IS NOT NULL) OR (status='REVERTED' AND canonical_id IS NULL)),
    CONSTRAINT ck_graph_resolution_source_type CHECK (source_type IN ('WIKI','TASK')),
    CONSTRAINT ck_graph_resolution_source_version CHECK (source_version >= 0),
    CONSTRAINT ck_graph_resolution_canonical_source_version CHECK (canonical_source_version >= 0),
    CONSTRAINT ck_graph_resolution_aliases CHECK (jsonb_typeof(aliases) = 'array'),
    CONSTRAINT ck_graph_resolution_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_graph_resolution_version CHECK (version > 0)
);
CREATE INDEX idx_graph_resolution_canonical ON graph_resolution_member(project_id,canonical_id)
    WHERE status='CONFIRMED';
CREATE TABLE graph_resolution_event (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    entity_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    previous_canonical_id UUID,
    canonical_id UUID,
    actor_user_id UUID NOT NULL REFERENCES app_user(id),
    member_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_graph_resolution_event_action CHECK (action IN ('CONFIRM','REVERT')),
    CONSTRAINT ck_graph_resolution_event_version CHECK (member_version > 0)
);
CREATE INDEX idx_graph_resolution_event_member ON graph_resolution_event(project_id,entity_id,occurred_at);
CREATE TABLE graph_canonical_event (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    canonical_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL,
    previous_source_version BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    previous_name VARCHAR(200) NOT NULL,
    canonical_name VARCHAR(200) NOT NULL,
    previous_metadata JSONB NOT NULL,
    metadata JSONB NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES app_user(id),
    canonical_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_graph_canonical_event_target FOREIGN KEY (project_id,canonical_id)
        REFERENCES graph_canonical_entity(project_id,id),
    CONSTRAINT ck_graph_canonical_event_version CHECK (canonical_version > 0),
    CONSTRAINT ck_graph_canonical_event_action CHECK (action IN ('UPDATE','REFRESH_SOURCE')),
    CONSTRAINT ck_graph_canonical_event_sources CHECK (previous_source_version >= 0 AND source_version >= 0)
);
CREATE INDEX idx_graph_canonical_event_target ON graph_canonical_event(project_id,canonical_id,occurred_at);
