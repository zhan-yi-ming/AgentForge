#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout

PREVIOUS_FILE="${STATE_DIR}/previous-release"
[[ -s "${PREVIOUS_FILE}" ]] || { echo "No previous release is recorded." >&2; exit 1; }
TARGET="$(cat "${PREVIOUS_FILE}")"
cd "${REPO_DIR}"
[[ -z "$(git status --porcelain)" ]] || { echo "Production worktree is not clean." >&2; exit 1; }
git switch --detach "${TARGET}"
"${REPO_DIR}/scripts/deploy/deploy.sh"
"${REPO_DIR}/scripts/deploy/health-check.sh"
echo "Rolled back application code to ${TARGET}. Database volumes were preserved."
