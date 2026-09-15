#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

APPLICATION_SERVICES=(postgres core-api agent-service web gateway)
OBSERVABILITY_SERVICES=(loki alloy grafana)
EXPECTED_APPLICATION_SERVICES=${#APPLICATION_SERVICES[@]}
RUNNING="$(compose ps --status running -q "${APPLICATION_SERVICES[@]}" | wc -l | tr -d ' ')"
[[ "${RUNNING}" == "${EXPECTED_APPLICATION_SERVICES}" ]] || {
    compose ps
    echo "Expected ${EXPECTED_APPLICATION_SERVICES} running application services, found ${RUNNING}." >&2
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
OBSERVABILITY_RUNNING="$(compose ps --status running -q "${OBSERVABILITY_SERVICES[@]}" | wc -l | tr -d ' ')"
if [[ "${OBSERVABILITY_RUNNING}" != "${#OBSERVABILITY_SERVICES[@]}" ]]; then
    echo "WARNING: Observability is degraded; expected ${#OBSERVABILITY_SERVICES[@]} running services, found ${OBSERVABILITY_RUNNING}." >&2
elif ! curl --resolve "${PUBLIC_HOST}:443:127.0.0.1" --fail --silent --show-error --max-time 10 \
    "https://${PUBLIC_HOST}/grafana/api/health" >/dev/null; then
    echo "WARNING: Observability is degraded; Grafana health endpoint is unavailable." >&2
else
    GRAFANA_STATUS="$(curl --resolve "${PUBLIC_HOST}:443:127.0.0.1" --silent --show-error --max-time 10 \
        "https://${PUBLIC_HOST}/grafana/api/search" -o /tmp/agentforge-health-body -w '%{http_code}' || true)"
    if [[ "${GRAFANA_STATUS}" != "401" ]]; then
        echo "WARNING: Observability authentication check expected 401, received ${GRAFANA_STATUS:-no response}." >&2
    fi
fi
rm -f /tmp/agentforge-health-body
trap - EXIT
echo "AgentForge application HTTPS and authentication are healthy at https://${PUBLIC_HOST}/"
