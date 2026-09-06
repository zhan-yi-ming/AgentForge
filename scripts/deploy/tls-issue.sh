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

CERTIFICATE_ARGS=(--cert-name "${PUBLIC_HOST}")
if [[ "${PUBLIC_HOST_TYPE}" == "ip" ]]; then
    CERTIFICATE_ARGS+=(--preferred-profile shortlived --ip-address "${PUBLIC_HOST}")
else
    CERTIFICATE_ARGS+=(-d "${PUBLIC_HOST}")
    if [[ -n "${PUBLIC_WWW_HOST}" ]]; then
        CERTIFICATE_ARGS+=(-d "${PUBLIC_WWW_HOST}")
    fi
fi

docker run --rm \
    -v "${TLS_ROOT}/letsencrypt:/etc/letsencrypt" \
    -v "${TLS_ROOT}/acme:/var/www/certbot" \
    certbot/certbot:latest certonly \
    --non-interactive --agree-tos "${EMAIL_ARGS[@]}" \
    --webroot -w /var/www/certbot \
    "${CERTIFICATE_ARGS[@]}"

"${REPO_DIR}/scripts/deploy/tls-sync.sh"
