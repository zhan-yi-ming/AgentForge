ALTER TABLE agent_message ADD COLUMN request_id VARCHAR(100);

CREATE UNIQUE INDEX uk_agent_message_exchange_role
    ON agent_message(conversation_id, request_id, role)
    WHERE request_id IS NOT NULL;
