CREATE SCHEMA IF NOT EXISTS agent_checkpoint;

ALTER TABLE agent_task_action
    ADD COLUMN action_workflow_version INTEGER,
    ADD CONSTRAINT agent_task_action_workflow_version_check
        CHECK (action_workflow_version IS NULL OR action_workflow_version = 1);
