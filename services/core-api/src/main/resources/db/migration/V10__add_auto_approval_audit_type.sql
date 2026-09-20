ALTER TABLE agent_action_audit_event
    DROP CONSTRAINT agent_action_audit_event_type_check;

ALTER TABLE agent_action_audit_event
    ADD CONSTRAINT agent_action_audit_event_type_check
    CHECK (event_type IN ('REQUESTED', 'APPROVED', 'AUTO_APPROVED', 'REJECTED', 'EXECUTED', 'FAILED'));
