#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

EXPECTED_SERVICES=5
RUNNING="$(compose ps --status running -q | wc -l | tr -d ' ')"
[[ "${RUNNING}" == "${EXPECTED_SERVICES}" ]] || {
    compose ps
    echo "Expected ${EXPECTED_SERVICES} running services, found ${RUNNING}." >&2
    exit 1
}

curl --resolve "${PUBLIC_HOST}:443:127.0.0.1" --fail --silent --show-error --max-time 10 \
    "https://${PUBLIC_HOST}/" >/dev/null
trap 'rm -f /tmp/agentforge-health-body' EXIT
STATUS="$(curl --resolve "${PUBLIC_HOST}:443:127.0.0.1" --silent --show-error --max-time 10 \
    "https://${PUBLIC_HOST}/api/v1/users/me" -o /tmp/agentforge-health-body -w '%{http_code}')"
[[ "${STATUS}" == "401" ]] || {
    echo "Expected unauthenticated API status 401, received ${STATUS}." >&2
    exit 1
}
rm -f /tmp/agentforge-health-body
trap - EXIT
echo "AgentForge HTTPS and authentication boundary are healthy at https://${PUBLIC_HOST}/"
