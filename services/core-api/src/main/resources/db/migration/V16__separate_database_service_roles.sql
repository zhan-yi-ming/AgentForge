DO $roles$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentforge_core') THEN
        CREATE ROLE agentforge_core NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentforge_agent') THEN
        CREATE ROLE agentforge_agent NOLOGIN;
    END IF;
END
$roles$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO agentforge_core, agentforge_agent;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO agentforge_core;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO agentforge_core;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM agentforge_agent;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM agentforge_agent;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE rag_chunk TO agentforge_agent;

GRANT USAGE, CREATE ON SCHEMA agent_checkpoint TO agentforge_agent;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA agent_checkpoint TO agentforge_agent;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA agent_checkpoint TO agentforge_agent;

ALTER SCHEMA agent_checkpoint OWNER TO agentforge_agent;
DO $checkpoint_owners$
DECLARE
    checkpoint_table record;
    checkpoint_sequence record;
BEGIN
    FOR checkpoint_table IN
        SELECT tablename
        FROM pg_tables
        WHERE schemaname = 'agent_checkpoint'
    LOOP
        EXECUTE format(
            'ALTER TABLE agent_checkpoint.%I OWNER TO agentforge_agent',
            checkpoint_table.tablename
        );
    END LOOP;
    FOR checkpoint_sequence IN
        SELECT sequencename
        FROM pg_sequences
        WHERE schemaname = 'agent_checkpoint'
    LOOP
        EXECUTE format(
            'ALTER SEQUENCE agent_checkpoint.%I OWNER TO agentforge_agent',
            checkpoint_sequence.sequencename
        );
    END LOOP;
END
$checkpoint_owners$;
