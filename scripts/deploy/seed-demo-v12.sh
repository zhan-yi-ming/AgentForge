#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config
load_demo_config

RANDOM_EMAIL="demo-$(date -u +%Y%m%d%H%M%S)-$(openssl rand -hex 3)@agentforge.local"
RANDOM_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=')Aa1!"
BASE_URL="http://localhost:8080"
CREDENTIALS_FILE="${STATE_DIR}/demo-credentials.txt"

restore_registration() {
    unset AGENTFORGE_REGISTRATION_ENABLED || true
    compose up -d --force-recreate core-api >/dev/null
    compose up -d gateway >/dev/null
}
trap restore_registration EXIT

api_post() {
    local path="$1"
    local body="$2"
    local auth_header="${3:-}"
    if [[ -n "${auth_header}" ]]; then
        printf '%s' "${body}" | compose exec -T core-api curl --fail-with-body --silent --show-error \
            -H 'Content-Type: application/json' -H "${auth_header}" --data-binary @- "${BASE_URL}${path}"
    else
        printf '%s' "${body}" | compose exec -T core-api curl --fail-with-body --silent --show-error \
            -H 'Content-Type: application/json' --data-binary @- "${BASE_URL}${path}"
    fi
}

authenticate_or_register() {
    local email="$1"
    local password="$2"
    local display_name="$3"
    local login_body auth register_body
    login_body="$(jq -n --arg email "${email}" --arg password "${password}" \
        '{email:$email,password:$password}')"
    if auth="$(api_post '/api/v1/auth/login' "${login_body}" 2>/dev/null)"; then
        printf '%s' "${auth}"
        return
    fi
    register_body="$(jq -n --arg email "${email}" --arg password "${password}" --arg display "${display_name}" \
        '{email:$email,displayName:$display,password:$password}')"
    api_post '/api/v1/auth/register' "${register_body}"
}

seed_workspace() {
    local auth="$1"
    local workspace_description="$2"
    local token auth_header projects project_id project
    token="$(jq -er '.accessToken' <<<"${auth}")"
    [[ "$(jq -er '.user.role' <<<"${auth}")" == "USER" ]] || {
        echo "Demo accounts must have USER role." >&2
        exit 1
    }
    auth_header="Authorization: Bearer ${token}"
    projects="$(compose exec -T core-api curl --fail --silent -H "${auth_header}" "${BASE_URL}/api/v1/projects")"
    project_id="$(jq -r '.[] | select(.name == "AgentForge Demo") | .id' <<<"${projects}" | head -n 1)"
    [[ -z "${project_id}" ]] || return
    project="$(api_post '/api/v1/projects' "$(jq -n --arg description "${workspace_description}" \
        '{name:"AgentForge Demo",description:$description}')" "${auth_header}")"
    project_id="$(jq -er '.id' <<<"${project}")"
    api_post "/api/v1/projects/${project_id}/wiki-pages" \
        '{"title":"V1.2 Architecture","content":"# AgentForge V1.2\n\nJava owns authentication, quotas, approval and deterministic writes. Python owns LangGraph reasoning, RAG and native model streaming."}' \
        "${auth_header}" >/dev/null
    api_post "/api/v1/projects/${project_id}/tasks" \
        '{"title":"Explore the V1.2 workspace","description":"Ask Agent about the architecture and watch the answer stream, then review a proposed task.","status":"TODO","priority":"HIGH"}' \
        "${auth_header}" >/dev/null
}

compose stop gateway >/dev/null
export AGENTFORGE_REGISTRATION_ENABLED=true
compose up -d --force-recreate core-api >/dev/null
CORE_READY=false
for _ in $(seq 1 30); do
    if compose exec -T core-api curl --fail --silent http://localhost:8080/actuator/health >/dev/null; then
        CORE_READY=true
        break
    fi
    sleep 2
done
[[ "${CORE_READY}" == "true" ]] || { echo "Core API did not become healthy in time." >&2; exit 1; }

FIXED_AUTH="$(authenticate_or_register "${AGENTFORGE_DEMO_FIXED_EMAIL}" \
    "${AGENTFORGE_DEMO_FIXED_PASSWORD}" 'AgentForge Interview Demo')"
seed_workspace "${FIXED_AUTH}" 'Stable interview workspace maintained by zhan-yi-ming'
RANDOM_AUTH="$(authenticate_or_register "${RANDOM_EMAIL}" "${RANDOM_PASSWORD}" 'AgentForge Backup Demo')"
seed_workspace "${RANDOM_AUTH}" 'Random backup workspace for one-time sharing'

restore_registration
trap - EXIT
umask 077
{
    printf 'Demo URL: https://%s/\n' "${PUBLIC_HOST}"
    printf 'Fixed email: %s\n' "${AGENTFORGE_DEMO_FIXED_EMAIL}"
    printf 'Fixed password: %s\n' "${AGENTFORGE_DEMO_FIXED_PASSWORD}"
    printf 'Random email: %s\n' "${RANDOM_EMAIL}"
    printf 'Random password: %s\n' "${RANDOM_PASSWORD}"
} >"${CREDENTIALS_FILE}"
chmod 600 "${CREDENTIALS_FILE}"
printf 'Demo URL: https://%s/\nFixed email: %s\nCredentials saved to %s (mode 600).\n' \
    "${PUBLIC_HOST}" "${AGENTFORGE_DEMO_FIXED_EMAIL}" "${CREDENTIALS_FILE}"
