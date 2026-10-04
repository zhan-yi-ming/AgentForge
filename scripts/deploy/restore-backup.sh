#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

[[ $# -eq 1 ]] || { echo "Usage: $0 <backup-directory>" >&2; exit 2; }
BACKUP_ROOT="$(realpath -e -- "${BACKUP_DIR}")"
SOURCE="$(realpath -e -- "$1")"
[[ -d "${SOURCE}" && "$(dirname "${SOURCE}")" == "${BACKUP_ROOT}" && "$(basename "${SOURCE}")" == agentforge-* ]] || {
    echo "Backup must be a direct agentforge-* directory under ${BACKUP_ROOT}." >&2
    exit 2
}
[[ -s "${SOURCE}/postgres.dump" && -s "${SOURCE}/manifest" && -s "${SOURCE}/SHA256SUMS" ]] || {
    echo "Backup is missing required PostgreSQL, manifest, or checksum files." >&2
    exit 1
}
grep -Fxq 'format=agentforge-backup-v2' "${SOURCE}/manifest" || { echo "Unsupported backup format." >&2; exit 1; }
NEO4J_STATE="$(sed -n 's/^neo4j=//p' "${SOURCE}/manifest")"
[[ "${NEO4J_STATE}" == present || "${NEO4J_STATE}" == absent ]] || { echo "Invalid Neo4j manifest state." >&2; exit 1; }
if [[ "${NEO4J_STATE}" == present ]]; then
    [[ -s "${SOURCE}/neo4j.dump" ]] || { echo "Backup manifest requires neo4j.dump." >&2; exit 1; }
else
    GRAPH_CONTAINER="$(compose ps -a -q neo4j 2>/dev/null || true)"
    [[ -z "${GRAPH_CONTAINER}" ]] || {
        echo "A PostgreSQL-only backup cannot replace a deployment with managed Neo4j data." >&2
        exit 1
    }
fi
(
    cd "${SOURCE}"
    sha256sum --check SHA256SUMS
)

RUNNING_SERVICES=()
for service in gateway core-api agent-service neo4j; do
    if compose ps --status running --services "${service}" 2>/dev/null | grep -Fxq "${service}"; then
        RUNNING_SERVICES+=("${service}")
    fi
done

compose stop gateway core-api agent-service
if [[ "${NEO4J_STATE}" == present ]]; then
    compose stop neo4j
fi
compose up -d --wait postgres

if ! compose exec -T postgres pg_restore -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    --clean --if-exists --no-owner <"${SOURCE}/postgres.dump"; then
    echo "PostgreSQL restore failed; application services remain stopped." >&2
    exit 1
fi

if [[ "${NEO4J_STATE}" == present ]]; then
    RESTORE_STAGE="${STATE_DIR}/neo4j-restore-$$"
    [[ ! -e "${RESTORE_STAGE}" ]] || { echo "Neo4j restore staging path already exists." >&2; exit 1; }
    install -d -m 0700 -o 7474 -g 7474 "${RESTORE_STAGE}"
    install -m 0600 -o 7474 -g 7474 "${SOURCE}/neo4j.dump" "${RESTORE_STAGE}/neo4j.dump"
    if ! compose run --rm --no-deps -v "${RESTORE_STAGE}:/backups:ro" neo4j \
        neo4j-admin database load neo4j --from-path=/backups --overwrite-destination=true; then
        rm -f -- "${RESTORE_STAGE}/neo4j.dump"
        rmdir "${RESTORE_STAGE}"
        echo "Neo4j restore failed; application services remain stopped." >&2
        exit 1
    fi
    rm -f -- "${RESTORE_STAGE}/neo4j.dump"
    rmdir "${RESTORE_STAGE}"
fi

if [[ ${#RUNNING_SERVICES[@]} -gt 0 ]]; then
    compose up -d "${RUNNING_SERVICES[@]}"
fi
echo "Restore completed from ${SOURCE}. Run scripts/deploy/health-check.sh and the graph recovery smoke before reopening maintenance access."
