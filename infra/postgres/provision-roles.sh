#!/usr/bin/env bash
set -Eeuo pipefail

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
: "${AGENTFORGE_CORE_DB_PASSWORD:?AGENTFORGE_CORE_DB_PASSWORD is required}"
: "${AGENTFORGE_AGENT_DB_PASSWORD:?AGENTFORGE_AGENT_DB_PASSWORD is required}"

[[ "${POSTGRES_USER}" =~ ^[a-z_][a-z0-9_]*$ ]] || {
    echo "POSTGRES_USER must be a safe unquoted PostgreSQL role name." >&2
    exit 1
}
[[ "${POSTGRES_DB}" =~ ^[a-z_][a-z0-9_]*$ ]] || {
    echo "POSTGRES_DB must be a safe PostgreSQL database name." >&2
    exit 1
}
for password in "${POSTGRES_PASSWORD}" "${AGENTFORGE_CORE_DB_PASSWORD}" "${AGENTFORGE_AGENT_DB_PASSWORD}"; do
    [[ "${password}" =~ ^[A-Za-z0-9_-]{12,}$ ]] || {
        echo "Database passwords must be at least 12 URL-safe characters." >&2
        exit 1
    }
done

export PGPASSWORD="${POSTGRES_PASSWORD}"
psql --no-psqlrc --set ON_ERROR_STOP=1 --host "${PGHOST:-postgres}" \
    --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<SQL
DO \$roles\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentforge_core') THEN
        CREATE ROLE agentforge_core LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentforge_agent') THEN
        CREATE ROLE agentforge_agent LOGIN;
    END IF;
END
\$roles\$;

ALTER ROLE agentforge_core WITH LOGIN PASSWORD '${AGENTFORGE_CORE_DB_PASSWORD}'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE agentforge_agent WITH LOGIN PASSWORD '${AGENTFORGE_AGENT_DB_PASSWORD}'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO agentforge_core, agentforge_agent;
GRANT USAGE ON SCHEMA public TO agentforge_core;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO agentforge_core;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO agentforge_core;
ALTER DEFAULT PRIVILEGES FOR ROLE "${POSTGRES_USER}" IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO agentforge_core;
ALTER DEFAULT PRIVILEGES FOR ROLE "${POSTGRES_USER}" IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO agentforge_core;
SQL

echo "Database service roles are ready."
