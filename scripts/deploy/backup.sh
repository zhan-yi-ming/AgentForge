#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="${BACKUP_DIR}/agentforge-${STAMP}"
PARTIAL="${TARGET}.partial"
PUBLISHED=false
QUIESCED=false
RUNNING_SERVICES=()

for service in gateway core-api agent-service neo4j; do
    if compose ps --status running --services "${service}" 2>/dev/null | grep -Fxq "${service}"; then
        RUNNING_SERVICES+=("${service}")
    fi
done

finish() {
    local result=$?
    if [[ "${PUBLISHED}" != true && -d "${PARTIAL}" && "${PARTIAL}" == "${BACKUP_DIR}"/agentforge-*.partial ]]; then
        rm -rf -- "${PARTIAL}"
    fi
    if [[ "${QUIESCED}" == true && ${#RUNNING_SERVICES[@]} -gt 0 ]]; then
        if ! compose up -d "${RUNNING_SERVICES[@]}"; then
            echo "Backup finished but one or more previously running services did not restart." >&2
            result=1
        fi
    fi
    trap - EXIT
    exit "${result}"
}
trap finish EXIT

[[ ! -e "${TARGET}" && ! -e "${PARTIAL}" ]] || {
    echo "Backup destination already exists: ${TARGET}" >&2
    exit 1
}
install -d -m 0750 -o root -g 7474 "${PARTIAL}"
umask 077

compose stop gateway core-api agent-service
QUIESCED=true
compose exec -T postgres pg_dump -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Fc >"${PARTIAL}/postgres.dump"
[[ -s "${PARTIAL}/postgres.dump" ]] || { echo "PostgreSQL dump is empty." >&2; exit 1; }

GRAPH_CONTAINER="$(compose ps -a -q neo4j 2>/dev/null || true)"
if [[ -n "${GRAPH_CONTAINER}" ]]; then
    compose stop neo4j
    install -d -m 0700 -o 7474 -g 7474 "${PARTIAL}/neo4j"
    compose run --rm --no-deps -v "${PARTIAL}/neo4j:/backups" neo4j \
        neo4j-admin database dump neo4j --to-path=/backups --overwrite-destination=true
    [[ -s "${PARTIAL}/neo4j/neo4j.dump" ]] || { echo "Neo4j dump is empty." >&2; exit 1; }
    mv "${PARTIAL}/neo4j/neo4j.dump" "${PARTIAL}/neo4j.dump"
    rmdir "${PARTIAL}/neo4j"
    NEO4J_STATE=present
else
    GRAPH_ENABLED="$(sed -n 's/^AGENTFORGE_GRAPH_ENABLED=//p' "${ENV_FILE}" | tail -n 1)"
    [[ "${GRAPH_ENABLED,,}" != true ]] || {
        echo "Graph is enabled but no managed Neo4j container exists; refusing an incomplete backup." >&2
        exit 1
    }
    NEO4J_STATE=absent
fi

cat >"${PARTIAL}/manifest" <<EOF
format=agentforge-backup-v2
created_at=${STAMP}
neo4j=${NEO4J_STATE}
EOF
(
    cd "${PARTIAL}"
    artifacts=(postgres.dump manifest)
    [[ "${NEO4J_STATE}" != present ]] || artifacts+=(neo4j.dump)
    sha256sum "${artifacts[@]}" >SHA256SUMS
)
chown -R root:root "${PARTIAL}"
find "${PARTIAL}" -type d -exec chmod 0700 {} +
find "${PARTIAL}" -type f -exec chmod 0600 {} +
mv "${PARTIAL}" "${TARGET}"
PUBLISHED=true

find "${BACKUP_DIR}" -mindepth 1 -maxdepth 1 -type d -name 'agentforge-*' ! -name '*.partial' -mtime +14 -exec rm -rf -- {} +
echo "Backup created: ${TARGET} (neo4j=${NEO4J_STATE})"
