ALTER TABLE agent_conversation ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_agent_task_action_conversation_open
    ON agent_task_action(project_id, requested_by_user_id, conversation_id)
    WHERE status IN ('PENDING', 'APPROVED');
