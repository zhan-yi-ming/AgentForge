#!/usr/bin/env bash
set -Eeuo pipefail

REPO_DIR="${AGENTFORGE_REPO_DIR:-/opt/agentforge/repo}"
ENV_FILE="${AGENTFORGE_ENV_FILE:-/opt/agentforge/env/.env}"
COMPOSE_FILE="${REPO_DIR}/infra/compose.prod.yaml"
STATE_DIR="${AGENTFORGE_STATE_DIR:-/opt/agentforge/state}"
BACKUP_DIR="${AGENTFORGE_BACKUP_DIR:-/opt/agentforge/backups}"

require_root() {
    if [[ "${EUID}" -ne 0 ]]; then
        echo "Run this command as root." >&2
        exit 1
    fi
}

require_layout() {
    [[ -f "${COMPOSE_FILE}" ]] || { echo "Missing ${COMPOSE_FILE}" >&2; exit 1; }
    [[ -f "${ENV_FILE}" ]] || { echo "Missing ${ENV_FILE}" >&2; exit 1; }
    mkdir -p "${STATE_DIR}" "${BACKUP_DIR}"
}

compose() {
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

load_public_config() {
    PUBLIC_HOST="$(sed -n 's/^PUBLIC_HOST=//p' "${ENV_FILE}" | tail -n 1)"
    POSTGRES_DB="$(sed -n 's/^POSTGRES_DB=//p' "${ENV_FILE}" | tail -n 1)"
    POSTGRES_USER="$(sed -n 's/^POSTGRES_USER=//p' "${ENV_FILE}" | tail -n 1)"
    [[ -n "${PUBLIC_HOST}" && -n "${POSTGRES_DB}" && -n "${POSTGRES_USER}" ]] || {
        echo "PUBLIC_HOST, POSTGRES_DB and POSTGRES_USER are required." >&2
        exit 1
    }
}

load_demo_config() {
    AGENTFORGE_DEMO_FIXED_EMAIL="$(sed -n 's/^AGENTFORGE_DEMO_FIXED_EMAIL=//p' "${ENV_FILE}" | tail -n 1)"
    AGENTFORGE_DEMO_FIXED_PASSWORD="$(sed -n 's/^AGENTFORGE_DEMO_FIXED_PASSWORD=//p' "${ENV_FILE}" | tail -n 1)"
    [[ -n "${AGENTFORGE_DEMO_FIXED_EMAIL}" && -n "${AGENTFORGE_DEMO_FIXED_PASSWORD}" ]] || {
        echo "Fixed Demo credentials are required in the protected environment file." >&2
        exit 1
    }
}
