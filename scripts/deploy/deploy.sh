#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout

"${REPO_DIR}/scripts/deploy/validate-env.sh"
"${REPO_DIR}/scripts/deploy/init-tls.sh"
compose config --quiet
compose up -d postgres
compose build --no-deps core-api
compose up -d core-api
compose build --no-deps agent-service
compose up -d agent-service
compose build --no-deps web
compose up -d web
compose pull gateway
compose up -d gateway
compose ps
