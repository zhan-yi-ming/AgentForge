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

synchronize_fixed_account_password() {
    local email="$1"
    local password="$2"
    compose exec -T postgres psql -X --set=ON_ERROR_STOP=1 \
        -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
        --set=demo_email="${email}" --set=demo_password="${password}" >/dev/null <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE app_user
SET password_hash = '{bcrypt}' || crypt(:'demo_password', gen_salt('bf', 10)),
    updated_at = now()
WHERE email=lower(:'demo_email') AND role='USER';
SQL
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
    local token auth_header projects project_id project wiki_pages tasks
    token="$(jq -er '.accessToken' <<<"${auth}")"
    [[ "$(jq -er '.user.role' <<<"${auth}")" == "USER" ]] || {
        echo "Demo accounts must have USER role." >&2
        exit 1
    }
    auth_header="Authorization: Bearer ${token}"
    projects="$(compose exec -T core-api curl --fail --silent -H "${auth_header}" "${BASE_URL}/api/v1/projects")"
    project_id="$(jq -r '.[] | select(.name == "AgentForge Demo") | .id' <<<"${projects}" | head -n 1)"
    if [[ -z "${project_id}" ]]; then
        project="$(api_post '/api/v1/projects' "$(jq -n --arg description "${workspace_description}" \
            '{name:"AgentForge Demo",description:$description}')" "${auth_header}")"
        project_id="$(jq -er '.id' <<<"${project}")"
    fi
    wiki_pages="$(compose exec -T core-api curl --fail --silent -H "${auth_header}" \
        "${BASE_URL}/api/v1/projects/${project_id}/wiki-pages")"
    tasks="$(compose exec -T core-api curl --fail --silent -H "${auth_header}" \
        "${BASE_URL}/api/v1/projects/${project_id}/tasks")"

    ensure_wiki_page() {
        local title="$1" content="$2"
        jq -e --arg title "${title}" '.[] | select(.title == $title)' <<<"${wiki_pages}" >/dev/null ||
            api_post "/api/v1/projects/${project_id}/wiki-pages" \
                "$(jq -n --arg title "${title}" --arg content "${content}" '{title:$title,content:$content}')" \
                "${auth_header}" >/dev/null
    }

    ensure_task() {
        local title="$1" description="$2" status="$3" priority="$4"
        jq -e --arg title "${title}" '.[] | select(.title == $title)' <<<"${tasks}" >/dev/null ||
            api_post "/api/v1/projects/${project_id}/tasks" \
                "$(jq -n --arg title "${title}" --arg description "${description}" \
                    --arg status "${status}" --arg priority "${priority}" \
                    '{title:$title,description:$description,status:$status,priority:$priority}')" \
                "${auth_header}" >/dev/null
    }

    ensure_wiki_page 'AgentForge V2 Architecture' $'# AgentForge V2 Architecture\n\nAgentForge is a reliable AI Agent workspace for engineering collaboration.\n\n## Deterministic boundary\n\n- Python owns context engineering, retrieval, planning and model interaction.\n- Java owns authentication, RBAC, risk policy, approval and business writes.\n- The model proposes intent; deterministic services authorize and execute it.\n\n## Reliability path\n\nA request flows through project context, retrieval, Agent planning, tool policy, human approval, Java permission recheck, persistence, trace and evaluation.'
    ensure_wiki_page 'Security, Approval and Recovery' $'# Security, Approval and Recovery\n\nWrite operations never execute directly from model text. Tool metadata assigns role and risk, high-risk actions pause for approval, and Java rechecks authorization before an idempotent write. Audit records preserve who requested and decided the action. LangGraph checkpoints allow an interrupted workflow to resume after a service restart.'
    ensure_wiki_page 'Interview Demo Guide' $'# Interview Demo Guide\n\nTry these questions:\n\n1. AgentForge 为什么不是普通 RAG Wrapper？\n2. 创建或更新任务时，模型为什么不能直接写数据库？\n3. 如果审批期间 Python 服务重启，系统如何恢复？\n4. 请根据 Wiki 提议创建一个高优先级的安全回归任务。\n\nThe first three are read-only. The fourth should produce a reviewable proposal and wait for confirmation.'

    ensure_task 'Interview demo walkthrough' 'Ask an architecture question, inspect cited context, then request one task proposal and review the approval boundary.' 'IN_PROGRESS' 'HIGH'
    ensure_task 'Verify cross-user isolation' 'Confirm that each Demo account owns an independent seeded workspace copy and that deleting one copy does not affect another user.' 'TODO' 'HIGH'
    ensure_task 'Review V2 reliability evidence' 'Inspect context budgeting, RBAC/risk, approval/idempotency/audit, checkpoint recovery, tracing and evaluation evidence.' 'TODO' 'MEDIUM'
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

synchronize_fixed_account_password "${AGENTFORGE_DEMO_FIXED_EMAIL}" \
    "${AGENTFORGE_DEMO_FIXED_PASSWORD}"
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
