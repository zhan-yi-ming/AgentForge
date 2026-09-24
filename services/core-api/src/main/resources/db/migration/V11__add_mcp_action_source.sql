ALTER TABLE agent_task_action
    ADD COLUMN source VARCHAR(16),
    ADD COLUMN proposal_idempotency_key VARCHAR(100);

UPDATE agent_task_action
SET source = 'CHAT'
WHERE source IS NULL;

ALTER TABLE agent_task_action
    ALTER COLUMN source SET NOT NULL,
    ALTER COLUMN conversation_id DROP NOT NULL,
    ADD CONSTRAINT agent_task_action_source_check
        CHECK (source IN ('CHAT', 'MCP')),
    ADD CONSTRAINT agent_task_action_source_scope_check
        CHECK (
            (source = 'CHAT' AND conversation_id IS NOT NULL AND proposal_idempotency_key IS NULL)
            OR
            (source = 'MCP'
                AND conversation_id IS NULL
                AND action_workflow_version IS NULL
                AND proposal_idempotency_key IS NOT NULL)
        );

CREATE UNIQUE INDEX agent_task_action_mcp_proposal_key_uk
    ON agent_task_action(project_id, requested_by_user_id, proposal_idempotency_key)
    WHERE source = 'MCP';
