CREATE TABLE agent_conversation (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    preview VARCHAR(240) NOT NULL,
    message_count INTEGER NOT NULL DEFAULT 0 CHECK (message_count >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_agent_conversation_scope_updated
    ON agent_conversation(project_id, user_id, updated_at DESC);

CREATE TABLE agent_message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
    sequence_number BIGINT NOT NULL CHECK (sequence_number >= 0),
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content TEXT NOT NULL,
    sources_json TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_agent_message_conversation_sequence UNIQUE (conversation_id, sequence_number)
);

CREATE INDEX idx_agent_message_conversation_sequence
    ON agent_message(conversation_id, sequence_number);
