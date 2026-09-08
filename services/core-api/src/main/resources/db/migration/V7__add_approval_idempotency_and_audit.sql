ALTER TABLE agent_task_action DROP CONSTRAINT agent_task_action_status_check;

ALTER TABLE agent_task_action
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD CONSTRAINT agent_task_action_status_check
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXECUTED', 'FAILED'));

CREATE UNIQUE INDEX uq_agent_task_action_requester_idempotency
    ON agent_task_action(requested_by_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE agent_action_audit_event (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    approval_id UUID NOT NULL REFERENCES agent_task_action(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL REFERENCES app_user(id),
    action_type VARCHAR(24) NOT NULL,
    target_id UUID,
    event_type VARCHAR(16) NOT NULL,
    result VARCHAR(32) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT agent_action_audit_action_type_check CHECK (action_type IN ('CREATE_TASK', 'UPDATE_TASK')),
    CONSTRAINT agent_action_audit_event_type_check
        CHECK (event_type IN ('REQUESTED', 'APPROVED', 'REJECTED', 'EXECUTED', 'FAILED'))
);

CREATE INDEX idx_agent_action_audit_approval_created
    ON agent_action_audit_event(approval_id, created_at, id);

CREATE INDEX idx_agent_action_audit_project_created
    ON agent_action_audit_event(project_id, created_at DESC);
