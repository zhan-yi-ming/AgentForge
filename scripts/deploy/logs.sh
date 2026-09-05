#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout

SERVICE="${1:-all}"
if [[ "${SERVICE}" == "all" ]]; then
    compose logs --tail 200
else
    case "${SERVICE}" in
        postgres|core-api|agent-service|web|gateway) compose logs --tail 200 "${SERVICE}" ;;
        *) echo "Unknown service: ${SERVICE}" >&2; exit 1 ;;
    esac
fi
