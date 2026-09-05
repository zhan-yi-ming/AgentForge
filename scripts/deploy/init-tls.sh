#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

TLS_CURRENT_DIR="/opt/agentforge/tls/current"
if [[ ! -s "${TLS_CURRENT_DIR}/fullchain.pem" || ! -s "${TLS_CURRENT_DIR}/privkey.pem" ]]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
        -keyout "${TLS_CURRENT_DIR}/privkey.pem" \
        -out "${TLS_CURRENT_DIR}/fullchain.pem" \
        -subj "/CN=${PUBLIC_HOST}" \
        -addext "subjectAltName=IP:${PUBLIC_HOST}"
    chmod 600 "${TLS_CURRENT_DIR}/privkey.pem"
fi
