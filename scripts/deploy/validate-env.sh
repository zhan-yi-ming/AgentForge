#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout

MODE="$(stat -c '%a' "${ENV_FILE}")"
[[ "${MODE}" == "600" || "${MODE}" == "400" ]] || {
    echo "${ENV_FILE} must have mode 600 or 400; found ${MODE}." >&2
    exit 1
}
grep -q 'REPLACE_' "${ENV_FILE}" && {
    echo "Replace every REPLACE_ placeholder in ${ENV_FILE}." >&2
    exit 1
}

set -a
source "${ENV_FILE}"
set +a
: "${PUBLIC_HOST:?PUBLIC_HOST is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
: "${AGENTFORGE_JWT_SECRET:?AGENTFORGE_JWT_SECRET is required}"
: "${AGENTFORGE_AGENT_INTERNAL_TOKEN:?AGENTFORGE_AGENT_INTERNAL_TOKEN is required}"
: "${AGENTFORGE_CORE_INTERNAL_TOKEN:?AGENTFORGE_CORE_INTERNAL_TOKEN is required}"
: "${AGENTFORGE_DEMO_FIXED_EMAIL:?AGENTFORGE_DEMO_FIXED_EMAIL is required}"
: "${AGENTFORGE_DEMO_FIXED_PASSWORD:?AGENTFORGE_DEMO_FIXED_PASSWORD is required}"

[[ "${PUBLIC_HOST}" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo "PUBLIC_HOST must be IPv4." >&2; exit 1; }
[[ "${POSTGRES_PASSWORD}" =~ ^[A-Za-z0-9_-]{24,}$ ]] || {
    echo "POSTGRES_PASSWORD must be at least 24 URL-safe characters." >&2
    exit 1
}
[[ "${#AGENTFORGE_AGENT_INTERNAL_TOKEN}" -ge 32 && "${#AGENTFORGE_CORE_INTERNAL_TOKEN}" -ge 32 ]] || {
    echo "Internal tokens must be at least 32 characters." >&2
    exit 1
}
JWT_BYTES="$(printf '%s' "${AGENTFORGE_JWT_SECRET}" | base64 -d 2>/dev/null | wc -c | tr -d ' ')" || {
    echo "AGENTFORGE_JWT_SECRET must be valid Base64." >&2
    exit 1
}
[[ "${JWT_BYTES}" -ge 32 ]] || { echo "JWT secret must decode to at least 32 bytes." >&2; exit 1; }
case "${AGENTFORGE_AGENT_LLM_PROVIDER:-disabled}" in
    disabled) ;;
    deepseek|zhipu|qwen) [[ -n "${AGENTFORGE_AGENT_LLM_API_KEY:-}" ]] || {
        echo "The enabled LLM provider requires AGENTFORGE_AGENT_LLM_API_KEY." >&2
        exit 1
    } ;;
    *) echo "Unsupported LLM provider." >&2; exit 1 ;;
esac
[[ "${AGENTFORGE_REGISTRATION_ENABLED:-false}" == "false" ]] || {
    echo "Production deployment requires AGENTFORGE_REGISTRATION_ENABLED=false." >&2
    exit 1
}
[[ "${AGENTFORGE_AI_DAILY_LIMIT:-0}" =~ ^[1-9][0-9]*$ ]] || {
    echo "Production deployment requires a positive AGENTFORGE_AI_DAILY_LIMIT." >&2
    exit 1
}
[[ "${AGENTFORGE_DEMO_FIXED_EMAIL}" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || {
    echo "AGENTFORGE_DEMO_FIXED_EMAIL must be a valid email address." >&2
    exit 1
}
PUBLIC_DEMO_EMAIL="210168y@gmail.com"
PUBLIC_DEMO_PASSWORD="Z1060168"
if [[ "${AGENTFORGE_DEMO_FIXED_EMAIL}" != "${PUBLIC_DEMO_EMAIL}" || \
      "${AGENTFORGE_DEMO_FIXED_PASSWORD}" != "${PUBLIC_DEMO_PASSWORD}" ]]; then
    [[ "${#AGENTFORGE_DEMO_FIXED_PASSWORD}" -ge 12 && "${#AGENTFORGE_DEMO_FIXED_PASSWORD}" -le 72 ]] || {
        echo "A custom AGENTFORGE_DEMO_FIXED_PASSWORD must contain 12 to 72 characters." >&2
        exit 1
    }
fi
echo "Production environment validation passed."
