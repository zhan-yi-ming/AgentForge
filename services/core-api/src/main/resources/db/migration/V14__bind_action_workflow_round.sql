ALTER TABLE agent_task_action
    DROP CONSTRAINT agent_task_action_source_scope_check,
    DROP CONSTRAINT agent_task_action_workflow_version_check,
    ADD COLUMN action_workflow_id UUID,
    ADD CONSTRAINT agent_task_action_workflow_version_check
        CHECK (action_workflow_version IS NULL OR action_workflow_version IN (1, 2)),
    ADD CONSTRAINT agent_task_action_source_scope_check
        CHECK (
            (source = 'CHAT'
                AND conversation_id IS NOT NULL
                AND proposal_idempotency_key IS NULL
                AND (
                    (action_workflow_version IS NULL AND action_workflow_id IS NULL)
                    OR (action_workflow_version = 1 AND action_workflow_id IS NULL)
                    OR (action_workflow_version = 2 AND action_workflow_id IS NOT NULL)
                ))
            OR
            (source = 'MCP'
                AND conversation_id IS NULL
                AND action_workflow_version IS NULL
                AND action_workflow_id IS NULL
                AND proposal_idempotency_key IS NOT NULL)
        );

CREATE UNIQUE INDEX agent_task_action_workflow_id_uk
    ON agent_task_action(action_workflow_id)
    WHERE action_workflow_id IS NOT NULL;
