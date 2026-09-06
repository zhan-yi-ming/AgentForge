#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout

docker run --rm \
    -v /opt/agentforge/tls/letsencrypt:/etc/letsencrypt \
    -v /opt/agentforge/tls/acme:/var/www/certbot \
    certbot/certbot:latest renew --non-interactive --quiet
"${REPO_DIR}/scripts/deploy/tls-sync.sh"
