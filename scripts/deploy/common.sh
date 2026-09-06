#!/usr/bin/env bash
set -Eeuo pipefail

REPO_DIR="${AGENTFORGE_REPO_DIR:-/opt/agentforge/repo}"
ENV_FILE="${AGENTFORGE_ENV_FILE:-/opt/agentforge/env/.env}"
COMPOSE_FILE="${REPO_DIR}/infra/compose.prod.yaml"
STATE_DIR="${AGENTFORGE_STATE_DIR:-/opt/agentforge/state}"
BACKUP_DIR="${AGENTFORGE_BACKUP_DIR:-/opt/agentforge/backups}"
TLS_ROOT="${AGENTFORGE_TLS_ROOT:-/opt/agentforge/tls}"

require_root() {
    if [[ "${EUID}" -ne 0 ]]; then
        echo "Run this command as root." >&2
        exit 1
    fi
}

require_layout() {
    [[ -f "${COMPOSE_FILE}" ]] || { echo "Missing ${COMPOSE_FILE}" >&2; exit 1; }
    [[ -f "${ENV_FILE}" ]] || { echo "Missing ${ENV_FILE}" >&2; exit 1; }
    mkdir -p "${STATE_DIR}" "${BACKUP_DIR}"
}

compose() {
    load_public_config
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

is_ipv4_address() {
    local candidate="$1"
    local octet
    local -a octets
    [[ "${candidate}" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
    IFS='.' read -r -a octets <<<"${candidate}"
    for octet in "${octets[@]}"; do
        ((10#${octet} <= 255)) || return 1
    done
}

is_ipv6_address() {
    local candidate="$1"
    local left right group
    local -a left_groups=() right_groups=() groups=()
    local explicit_group_count

    [[ "${candidate}" == *:* && "${candidate}" =~ ^[0-9A-Fa-f:]+$ ]] || return 1
    [[ "${candidate}" != '::' && "${candidate}" != *:::* ]] || return 1

    if [[ "${candidate}" == *::* ]]; then
        left="${candidate%%::*}"
        right="${candidate#*::}"
        [[ "${right}" != *::* ]] || return 1
        [[ -z "${left}" ]] || IFS=':' read -r -a left_groups <<<"${left}"
        [[ -z "${right}" ]] || IFS=':' read -r -a right_groups <<<"${right}"
        explicit_group_count=$((${#left_groups[@]} + ${#right_groups[@]}))
        ((explicit_group_count < 8)) || return 1
        groups=("${left_groups[@]}" "${right_groups[@]}")
    else
        IFS=':' read -r -a groups <<<"${candidate}"
        ((${#groups[@]} == 8)) || return 1
    fi

    for group in "${groups[@]}"; do
        [[ "${group}" =~ ^[0-9A-Fa-f]{1,4}$ ]] || return 1
    done
}

is_ip_address() {
    is_ipv4_address "$1" || is_ipv6_address "$1"
}

is_domain_name() {
    local candidate="$1"
    local label
    local -a labels
    [[ ${#candidate} -le 253 && "${candidate}" == *.* && "${candidate}" =~ [A-Za-z] ]] || return 1
    IFS='.' read -r -a labels <<<"${candidate}"
    for label in "${labels[@]}"; do
        [[ ${#label} -le 63 && "${label}" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$ ]] || return 1
    done
}

is_public_host() {
    is_ip_address "$1" || is_domain_name "$1"
}

load_public_config() {
    PUBLIC_HOST="$(sed -n 's/^PUBLIC_HOST=//p' "${ENV_FILE}" | tail -n 1)"
    POSTGRES_DB="$(sed -n 's/^POSTGRES_DB=//p' "${ENV_FILE}" | tail -n 1)"
    POSTGRES_USER="$(sed -n 's/^POSTGRES_USER=//p' "${ENV_FILE}" | tail -n 1)"
    [[ -n "${PUBLIC_HOST}" && -n "${POSTGRES_DB}" && -n "${POSTGRES_USER}" ]] || {
        echo "PUBLIC_HOST, POSTGRES_DB and POSTGRES_USER are required." >&2
        exit 1
    }
    is_public_host "${PUBLIC_HOST}" || {
        echo "PUBLIC_HOST must be a valid IPv4, IPv6, or DNS domain." >&2
        exit 1
    }
    if is_ip_address "${PUBLIC_HOST}"; then
        PUBLIC_HOST_TYPE="ip"
        PUBLIC_WWW_HOST=""
    else
        PUBLIC_HOST_TYPE="domain"
        if [[ "${PUBLIC_HOST,,}" == www.* ]]; then
            PUBLIC_WWW_HOST=""
        else
            PUBLIC_WWW_HOST="www.${PUBLIC_HOST}"
        fi
    fi
    export PUBLIC_HOST PUBLIC_HOST_TYPE PUBLIC_WWW_HOST
}

load_demo_config() {
    AGENTFORGE_DEMO_FIXED_EMAIL="$(sed -n 's/^AGENTFORGE_DEMO_FIXED_EMAIL=//p' "${ENV_FILE}" | tail -n 1)"
    AGENTFORGE_DEMO_FIXED_PASSWORD="$(sed -n 's/^AGENTFORGE_DEMO_FIXED_PASSWORD=//p' "${ENV_FILE}" | tail -n 1)"
    [[ -n "${AGENTFORGE_DEMO_FIXED_EMAIL}" && -n "${AGENTFORGE_DEMO_FIXED_PASSWORD}" ]] || {
        echo "Fixed Demo credentials are required in the protected environment file." >&2
        exit 1
    }
}
