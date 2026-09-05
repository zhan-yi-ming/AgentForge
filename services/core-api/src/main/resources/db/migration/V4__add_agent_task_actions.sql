CREATE TABLE agent_task_action (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    requested_by_user_id UUID NOT NULL REFERENCES app_user(id),
    conversation_id UUID NOT NULL,
    action_type VARCHAR(24) NOT NULL,
    task_id UUID,
    title VARCHAR(200),
    description VARCHAR(10000),
    task_status VARCHAR(20),
    priority VARCHAR(20),
    expected_task_version BIGINT,
    status VARCHAR(16) NOT NULL,
    result_task_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    CONSTRAINT agent_task_action_type_check CHECK (action_type IN ('CREATE_TASK', 'UPDATE_TASK')),
    CONSTRAINT agent_task_action_status_check CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED')),
    CONSTRAINT agent_task_action_task_status_check CHECK (task_status IS NULL OR task_status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    CONSTRAINT agent_task_action_priority_check CHECK (priority IS NULL OR priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT agent_task_action_shape_check CHECK (
        (action_type = 'CREATE_TASK' AND task_id IS NULL AND expected_task_version IS NULL AND title IS NOT NULL)
        OR
        (action_type = 'UPDATE_TASK' AND task_id IS NOT NULL AND expected_task_version >= 0)
    )
);

CREATE INDEX idx_agent_task_action_project_created
    ON agent_task_action(project_id, created_at DESC);
