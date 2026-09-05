#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

DEMO_EMAIL="${DEMO_EMAIL:-demo@agentforge.local}"
DEMO_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=')Aa1!"
BASE_URL="http://localhost:8080"

restore_registration() {
    unset AGENTFORGE_REGISTRATION_ENABLED || true
    compose up -d --force-recreate core-api >/dev/null
    compose up -d gateway >/dev/null
}
trap restore_registration EXIT

compose stop gateway >/dev/null
export AGENTFORGE_REGISTRATION_ENABLED=true
compose up -d --force-recreate core-api >/dev/null
for _ in $(seq 1 30); do
    compose exec -T core-api curl --fail --silent http://localhost:8080/actuator/health >/dev/null && break
    sleep 2
done

REGISTER_BODY="$(jq -n --arg email "${DEMO_EMAIL}" --arg password "${DEMO_PASSWORD}" \
    '{email:$email,displayName:"AgentForge Demo",password:$password}')"
AUTH="$(compose exec -T core-api curl --fail-with-body --silent --show-error \
    -H 'Content-Type: application/json' \
    -d "${REGISTER_BODY}" "${BASE_URL}/api/v1/auth/register")" || {
        echo "Demo registration failed. The account may already exist; use a new DEMO_EMAIL." >&2
        exit 1
    }
TOKEN="$(jq -er '.accessToken' <<<"${AUTH}")"
AUTH_HEADER="Authorization: Bearer ${TOKEN}"

PROJECT="$(compose exec -T core-api curl --fail --silent -H "${AUTH_HEADER}" \
    -H 'Content-Type: application/json' -d '{"name":"AgentForge Demo","description":"Public V1.1 demo workspace"}' \
    "${BASE_URL}/api/v1/projects")"
PROJECT_ID="$(jq -er '.id' <<<"${PROJECT}")"
compose exec -T core-api curl --fail --silent -H "${AUTH_HEADER}" \
    -H 'Content-Type: application/json' \
    -d '{"title":"V1 Architecture","content":"# AgentForge V1\n\nJava owns authentication and deterministic writes. Python owns Agent reasoning and RAG."}' \
    "${BASE_URL}/api/v1/projects/${PROJECT_ID}/wiki-pages" >/dev/null
compose exec -T core-api curl --fail --silent -H "${AUTH_HEADER}" \
    -H 'Content-Type: application/json' \
    -d '{"title":"Try the V1.1 workspace","description":"Ask Agent about architecture, then confirm a proposed task.","status":"TODO","priority":"HIGH"}' \
    "${BASE_URL}/api/v1/projects/${PROJECT_ID}/tasks" >/dev/null

restore_registration
trap - EXIT
printf 'Demo URL: https://%s/\nDemo email: %s\nDemo password (shown once): %s\n' \
    "${PUBLIC_HOST}" "${DEMO_EMAIL}" "${DEMO_PASSWORD}"
