#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout

"${REPO_DIR}/scripts/deploy/validate-env.sh"
"${REPO_DIR}/scripts/deploy/init-tls.sh"
compose config --quiet
compose up -d postgres
compose build core-api
compose up -d core-api
compose build agent-service
compose up -d agent-service
compose build web
compose up -d web
compose pull gateway
compose up -d gateway
compose ps
