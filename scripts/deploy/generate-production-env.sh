#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run this command as root." >&2
    exit 1
fi
PUBLIC_HOST="${1:-}"
LLM_PROVIDER="${2:-deepseek}"
TARGET="${AGENTFORGE_ENV_FILE:-/opt/agentforge/env/.env}"
[[ "${PUBLIC_HOST}" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || {
    echo "Usage: $0 <public-ipv4> [deepseek|zhipu|qwen|disabled]" >&2
    exit 1
}
case "${LLM_PROVIDER}" in deepseek|zhipu|qwen|disabled) ;; *) echo "Unsupported provider." >&2; exit 1 ;; esac
[[ ! -e "${TARGET}" ]] || { echo "Refusing to overwrite ${TARGET}." >&2; exit 1; }

install -d -m 0700 "$(dirname "${TARGET}")"
umask 077
DB_PASSWORD="$(openssl rand -hex 24)"
JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
AGENT_TOKEN="$(openssl rand -hex 32)"
CORE_TOKEN="$(openssl rand -hex 32)"
cat >"${TARGET}" <<EOF
PUBLIC_HOST=${PUBLIC_HOST}
POSTGRES_DB=agentforge
POSTGRES_USER=agentforge
POSTGRES_PASSWORD=${DB_PASSWORD}
AGENTFORGE_POSTGRES_VOLUME=agentforge-postgres-data
AGENTFORGE_JWT_SECRET=${JWT_SECRET}
AGENTFORGE_JWT_ISSUER=https://${PUBLIC_HOST}/core-api
AGENTFORGE_JWT_TTL=PT30M
AGENTFORGE_AGENT_INTERNAL_TOKEN=${AGENT_TOKEN}
AGENTFORGE_CORE_INTERNAL_TOKEN=${CORE_TOKEN}
AGENTFORGE_REGISTRATION_ENABLED=false
AGENTFORGE_AI_DAILY_LIMIT=30
AGENTFORGE_DEMO_FIXED_EMAIL=210168y@gmail.com
AGENTFORGE_DEMO_FIXED_PASSWORD=Z1060168
AGENTFORGE_AGENT_LLM_PROVIDER=${LLM_PROVIDER}
AGENTFORGE_AGENT_LLM_API_KEY=REPLACE_ON_SERVER_ONLY
AGENTFORGE_AGENT_LLM_BASE_URL=
AGENTFORGE_AGENT_LLM_MODEL=
AGENTFORGE_AGENT_LLM_MAX_TOKENS=800
LETSENCRYPT_EMAIL=
EOF
chmod 600 "${TARGET}"
echo "Created ${TARGET}. Replace AGENTFORGE_AGENT_LLM_API_KEY on the server before deployment."
echo "The public interview Demo account was added; keep all service credentials private."
