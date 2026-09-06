#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

docker run --rm \
    -v "${TLS_ROOT}/letsencrypt:/etc/letsencrypt" \
    -v "${TLS_ROOT}/acme:/var/www/certbot" \
    certbot/certbot:latest renew --non-interactive --quiet --cert-name "${PUBLIC_HOST}"
"${REPO_DIR}/scripts/deploy/tls-sync.sh"
