#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="${BACKUP_DIR}/agentforge-${STAMP}.dump"
umask 077
compose exec -T postgres pg_dump -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Fc >"${TARGET}"
gzip "${TARGET}"
find "${BACKUP_DIR}" -type f -name 'agentforge-*.dump.gz' -mtime +14 -delete
echo "Backup created: ${TARGET}.gz"
