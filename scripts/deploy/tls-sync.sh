#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

LIVE_DIR="/opt/agentforge/tls/letsencrypt/live/${PUBLIC_HOST}"
[[ -s "${LIVE_DIR}/fullchain.pem" && -s "${LIVE_DIR}/privkey.pem" ]] || {
    echo "No issued certificate found for ${PUBLIC_HOST}." >&2
    exit 1
}
install -m 0644 -T "${LIVE_DIR}/fullchain.pem" /opt/agentforge/tls/current/fullchain.pem
install -m 0600 -T "${LIVE_DIR}/privkey.pem" /opt/agentforge/tls/current/privkey.pem
compose exec -T gateway nginx -s reload
