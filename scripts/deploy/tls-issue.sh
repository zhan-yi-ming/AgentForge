#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_layout
load_public_config

EMAIL="$(sed -n 's/^LETSENCRYPT_EMAIL=//p' "${ENV_FILE}" | tail -n 1)"
EMAIL_ARGS=(--register-unsafely-without-email)
if [[ -n "${EMAIL}" ]]; then
    EMAIL_ARGS=(--email "${EMAIL}" --no-eff-email)
fi

docker run --rm \
    -v /opt/agentforge/tls/letsencrypt:/etc/letsencrypt \
    -v /opt/agentforge/tls/acme:/var/www/certbot \
    certbot/certbot:latest certonly \
    --non-interactive --agree-tos "${EMAIL_ARGS[@]}" \
    --preferred-profile shortlived --webroot -w /var/www/certbot \
    --ip-address "${PUBLIC_HOST}"

"${REPO_DIR}/scripts/deploy/tls-sync.sh"
