#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

TLS_CURRENT_DIR="${TLS_ROOT}/current"
if [[ ! -s "${TLS_CURRENT_DIR}/fullchain.pem" || ! -s "${TLS_CURRENT_DIR}/privkey.pem" ]]; then
    if [[ "${PUBLIC_HOST_TYPE}" == "ip" ]]; then
        SUBJECT_ALT_NAME="IP:${PUBLIC_HOST}"
    else
        SUBJECT_ALT_NAME="DNS:${PUBLIC_HOST}"
        if [[ -n "${PUBLIC_WWW_HOST}" ]]; then
            SUBJECT_ALT_NAME+=",DNS:${PUBLIC_WWW_HOST}"
        fi
    fi
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
        -keyout "${TLS_CURRENT_DIR}/privkey.pem" \
        -out "${TLS_CURRENT_DIR}/fullchain.pem" \
        -subj "/CN=${PUBLIC_HOST}" \
        -addext "subjectAltName=${SUBJECT_ALT_NAME}"
    chmod 600 "${TLS_CURRENT_DIR}/privkey.pem"
fi
