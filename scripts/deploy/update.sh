#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout

cd "${REPO_DIR}"
[[ -z "$(git status --porcelain)" ]] || { echo "Production worktree is not clean." >&2; exit 1; }
git rev-parse HEAD >"${STATE_DIR}/previous-release"
"${REPO_DIR}/scripts/deploy/backup.sh"
git fetch origin main
git merge --ff-only origin/main
"${REPO_DIR}/scripts/deploy/deploy.sh"
"${REPO_DIR}/scripts/deploy/health-check.sh"
