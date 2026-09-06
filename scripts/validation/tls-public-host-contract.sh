#!/usr/bin/env bash
set -Eeuo pipefail

REPO_UNDER_TEST="${REPO_UNDER_TEST:-/repo}"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT

fail() {
    echo "TLS contract failed: $*" >&2
    exit 1
}

assert_line() {
    local expected="$1"
    local file="$2"
    grep -Fqx -- "${expected}" "${file}" || fail "missing argument '${expected}'"
}

assert_no_line() {
    local unexpected="$1"
    local file="$2"
    if grep -Fqx -- "${unexpected}" "${file}"; then
        fail "unexpected argument '${unexpected}'"
    fi
}

run_ipv4_issue_and_sync() {
    local scenario_root="${TEST_ROOT}/ipv4"
    local tls_root="${scenario_root}/tls"
    local env_file="${scenario_root}/agentforge.env"
    local command_log="${scenario_root}/docker.log"
    local fake_bin="${scenario_root}/bin"
    mkdir -p "${fake_bin}" "${tls_root}/letsencrypt" "${tls_root}/acme" "${tls_root}/current"

    cat >"${env_file}" <<'EOF'
PUBLIC_HOST=47.76.95.86
POSTGRES_DB=agentforge
POSTGRES_USER=agentforge
LETSENCRYPT_EMAIL=
EOF
    chmod 600 "${env_file}"

    cat >"${fake_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
{
    echo '---'
    printf '%s\n' "$@"
} >>"${AGENTFORGE_TEST_COMMAND_LOG}"

if [[ " $* " == *" certbot/certbot:latest certonly "* ]]; then
    public_host="$(sed -n 's/^PUBLIC_HOST=//p' "${AGENTFORGE_ENV_FILE}" | tail -n 1)"
    live_dir="${AGENTFORGE_TLS_ROOT}/letsencrypt/live/${public_host}"
    mkdir -p "${live_dir}"
    printf 'ipv4 fullchain\n' >"${live_dir}/fullchain.pem"
    printf 'ipv4 private key\n' >"${live_dir}/privkey.pem"
fi
if [[ " $* " == *" certbot/certbot:latest renew "* ]]; then
    public_host="$(sed -n 's/^PUBLIC_HOST=//p' "${AGENTFORGE_ENV_FILE}" | tail -n 1)"
    live_dir="${AGENTFORGE_TLS_ROOT}/letsencrypt/live/${public_host}"
    printf 'renewed fullchain\n' >"${live_dir}/fullchain.pem"
    printf 'renewed private key\n' >"${live_dir}/privkey.pem"
fi
EOF
    chmod +x "${fake_bin}/docker"

    PATH="${fake_bin}:${PATH}" \
    AGENTFORGE_REPO_DIR="${REPO_UNDER_TEST}" \
    AGENTFORGE_ENV_FILE="${env_file}" \
    AGENTFORGE_TLS_ROOT="${tls_root}" \
    AGENTFORGE_TEST_COMMAND_LOG="${command_log}" \
        "${REPO_UNDER_TEST}/scripts/deploy/tls-issue.sh"

    assert_line '--cert-name' "${command_log}"
    assert_line '47.76.95.86' "${command_log}"
    assert_line '--ip-address' "${command_log}"
    assert_line '--preferred-profile' "${command_log}"
    assert_line 'shortlived' "${command_log}"
    assert_no_line '-d' "${command_log}"
    cmp -s "${tls_root}/letsencrypt/live/47.76.95.86/fullchain.pem" "${tls_root}/current/fullchain.pem" ||
        fail 'IPv4 fullchain was not synchronized from the stable live directory'
    cmp -s "${tls_root}/letsencrypt/live/47.76.95.86/privkey.pem" "${tls_root}/current/privkey.pem" ||
        fail 'IPv4 private key was not synchronized from the stable live directory'
    assert_line 'reload' "${command_log}"

    : >"${command_log}"
    PATH="${fake_bin}:${PATH}" \
    AGENTFORGE_REPO_DIR="${REPO_UNDER_TEST}" \
    AGENTFORGE_ENV_FILE="${env_file}" \
    AGENTFORGE_TLS_ROOT="${tls_root}" \
    AGENTFORGE_TEST_COMMAND_LOG="${command_log}" \
        "${REPO_UNDER_TEST}/scripts/deploy/tls-renew.sh"

    assert_line "${tls_root}/letsencrypt:/etc/letsencrypt" "${command_log}"
    assert_line "${tls_root}/acme:/var/www/certbot" "${command_log}"
    assert_line 'renew' "${command_log}"
    assert_line '--cert-name' "${command_log}"
    [[ "$(grep -Fxc -- '47.76.95.86' "${command_log}")" -eq 1 ]] ||
        fail 'renewal must select exactly the current PUBLIC_HOST cert-name'
    [[ "$(cat "${tls_root}/current/fullchain.pem")" == 'renewed fullchain' ]] ||
        fail 'renewed fullchain was not synchronized to current'
    [[ "$(cat "${tls_root}/current/privkey.pem")" == 'renewed private key' ]] ||
        fail 'renewed private key was not synchronized to current'
    assert_line 'reload' "${command_log}"
}

run_root_domain_issue_and_sync() {
    local scenario_root="${TEST_ROOT}/root-domain"
    local tls_root="${scenario_root}/tls"
    local env_file="${scenario_root}/agentforge.env"
    local command_log="${scenario_root}/docker.log"
    local fake_bin="${scenario_root}/bin"
    mkdir -p "${fake_bin}" "${tls_root}/letsencrypt" "${tls_root}/acme" "${tls_root}/current"

    cat >"${env_file}" <<'EOF'
PUBLIC_HOST=example.com
POSTGRES_DB=agentforge
POSTGRES_USER=agentforge
LETSENCRYPT_EMAIL=ops@example.com
EOF
    chmod 600 "${env_file}"

    cat >"${fake_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
{
    echo '---'
    printf '%s\n' "$@"
} >>"${AGENTFORGE_TEST_COMMAND_LOG}"

if [[ " $* " == *" certbot/certbot:latest certonly "* ]]; then
    public_host="$(sed -n 's/^PUBLIC_HOST=//p' "${AGENTFORGE_ENV_FILE}" | tail -n 1)"
    live_dir="${AGENTFORGE_TLS_ROOT}/letsencrypt/live/${public_host}"
    mkdir -p "${live_dir}"
    printf 'domain fullchain\n' >"${live_dir}/fullchain.pem"
    printf 'domain private key\n' >"${live_dir}/privkey.pem"
fi
printf 'ENV_PUBLIC_WWW_HOST=%s\n' "${PUBLIC_WWW_HOST-}" >>"${AGENTFORGE_TEST_COMMAND_LOG}"
EOF
    chmod +x "${fake_bin}/docker"

    PATH="${fake_bin}:${PATH}" \
    AGENTFORGE_REPO_DIR="${REPO_UNDER_TEST}" \
    AGENTFORGE_ENV_FILE="${env_file}" \
    AGENTFORGE_TLS_ROOT="${tls_root}" \
    AGENTFORGE_TEST_COMMAND_LOG="${command_log}" \
        "${REPO_UNDER_TEST}/scripts/deploy/tls-issue.sh"

    assert_line '--cert-name' "${command_log}"
    [[ "$(grep -Fxc -- 'example.com' "${command_log}")" -eq 2 ]] ||
        fail 'root domain must appear once as cert-name and once as a requested domain'
    [[ "$(grep -Fxc -- 'www.example.com' "${command_log}")" -eq 1 ]] ||
        fail 'root domain must request exactly one www alternative name'
    assert_line '-d' "${command_log}"
    assert_line 'ENV_PUBLIC_WWW_HOST=www.example.com' "${command_log}"
    assert_no_line '--ip-address' "${command_log}"
    assert_no_line '--preferred-profile' "${command_log}"
    cmp -s "${tls_root}/letsencrypt/live/example.com/fullchain.pem" "${tls_root}/current/fullchain.pem" ||
        fail 'domain fullchain was not synchronized from live/example.com'
    cmp -s "${tls_root}/letsencrypt/live/example.com/privkey.pem" "${tls_root}/current/privkey.pem" ||
        fail 'domain private key was not synchronized from live/example.com'
    assert_line 'reload' "${command_log}"
}

run_www_domain_issue_and_sync() {
    local scenario_root="${TEST_ROOT}/www-domain"
    local tls_root="${scenario_root}/tls"
    local env_file="${scenario_root}/agentforge.env"
    local command_log="${scenario_root}/docker.log"
    local fake_bin="${scenario_root}/bin"
    mkdir -p "${fake_bin}" "${tls_root}/letsencrypt" "${tls_root}/acme" "${tls_root}/current"

    cat >"${env_file}" <<'EOF'
PUBLIC_HOST=www.example.com
POSTGRES_DB=agentforge
POSTGRES_USER=agentforge
LETSENCRYPT_EMAIL=
EOF
    chmod 600 "${env_file}"

    cat >"${fake_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
{
    echo '---'
    printf '%s\n' "$@"
} >>"${AGENTFORGE_TEST_COMMAND_LOG}"
if [[ " $* " == *" certbot/certbot:latest certonly "* ]]; then
    public_host="$(sed -n 's/^PUBLIC_HOST=//p' "${AGENTFORGE_ENV_FILE}" | tail -n 1)"
    live_dir="${AGENTFORGE_TLS_ROOT}/letsencrypt/live/${public_host}"
    mkdir -p "${live_dir}"
    printf 'www fullchain\n' >"${live_dir}/fullchain.pem"
    printf 'www private key\n' >"${live_dir}/privkey.pem"
fi
EOF
    chmod +x "${fake_bin}/docker"

    PATH="${fake_bin}:${PATH}" \
    AGENTFORGE_REPO_DIR="${REPO_UNDER_TEST}" \
    AGENTFORGE_ENV_FILE="${env_file}" \
    AGENTFORGE_TLS_ROOT="${tls_root}" \
    AGENTFORGE_TEST_COMMAND_LOG="${command_log}" \
        "${REPO_UNDER_TEST}/scripts/deploy/tls-issue.sh"

    [[ "$(grep -Fxc -- '-d' "${command_log}")" -eq 1 ]] ||
        fail 'a PUBLIC_HOST already starting with www must produce exactly one -d argument'
    [[ "$(grep -Fxc -- 'www.example.com' "${command_log}")" -eq 2 ]] ||
        fail 'www domain must appear once as cert-name and once as the requested domain'
    if grep -Fq -- 'www.www.example.com' "${command_log}"; then
        fail 'www domain must never generate a duplicated www prefix'
    fi
    cmp -s "${tls_root}/letsencrypt/live/www.example.com/fullchain.pem" "${tls_root}/current/fullchain.pem" ||
        fail 'www domain fullchain was not synchronized from its stable live directory'
    assert_line 'reload' "${command_log}"
}

run_ipv6_issue_and_sync() {
    local scenario_root="${TEST_ROOT}/ipv6"
    local tls_root="${scenario_root}/tls"
    local env_file="${scenario_root}/agentforge.env"
    local command_log="${scenario_root}/docker.log"
    local fake_bin="${scenario_root}/bin"
    mkdir -p "${fake_bin}" "${tls_root}/letsencrypt" "${tls_root}/acme" "${tls_root}/current"
    cat >"${env_file}" <<'EOF'
PUBLIC_HOST=2001:db8::10
POSTGRES_DB=agentforge
POSTGRES_USER=agentforge
LETSENCRYPT_EMAIL=
EOF
    chmod 600 "${env_file}"

    cat >"${fake_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
{
    echo '---'
    printf '%s\n' "$@"
} >>"${AGENTFORGE_TEST_COMMAND_LOG}"
if [[ " $* " == *" certbot/certbot:latest certonly "* ]]; then
    public_host="$(sed -n 's/^PUBLIC_HOST=//p' "${AGENTFORGE_ENV_FILE}" | tail -n 1)"
    live_dir="${AGENTFORGE_TLS_ROOT}/letsencrypt/live/${public_host}"
    mkdir -p "${live_dir}"
    printf 'ipv6 fullchain\n' >"${live_dir}/fullchain.pem"
    printf 'ipv6 private key\n' >"${live_dir}/privkey.pem"
fi
EOF
    chmod +x "${fake_bin}/docker"

    PATH="${fake_bin}:${PATH}" \
    AGENTFORGE_REPO_DIR="${REPO_UNDER_TEST}" \
    AGENTFORGE_ENV_FILE="${env_file}" \
    AGENTFORGE_TLS_ROOT="${tls_root}" \
    AGENTFORGE_TEST_COMMAND_LOG="${command_log}" \
        "${REPO_UNDER_TEST}/scripts/deploy/tls-issue.sh"

    assert_line '--cert-name' "${command_log}"
    assert_line '--ip-address' "${command_log}"
    assert_line '--preferred-profile' "${command_log}"
    [[ "$(grep -Fxc -- '2001:db8::10' "${command_log}")" -eq 2 ]] ||
        fail 'IPv6 must appear once as cert-name and once as the requested IP address'
    assert_no_line '-d' "${command_log}"
    cmp -s "${tls_root}/letsencrypt/live/2001:db8::10/fullchain.pem" "${tls_root}/current/fullchain.pem" ||
        fail 'IPv6 fullchain was not synchronized from its stable live directory'
}

run_root_domain_bootstrap_certificate() {
    local scenario_root="${TEST_ROOT}/bootstrap-domain"
    local tls_root="${scenario_root}/tls"
    local env_file="${scenario_root}/agentforge.env"
    local openssl_log="${scenario_root}/openssl.log"
    local fake_bin="${scenario_root}/bin"
    mkdir -p "${fake_bin}" "${tls_root}/current"

    cat >"${env_file}" <<'EOF'
PUBLIC_HOST=example.com
POSTGRES_DB=agentforge
POSTGRES_USER=agentforge
EOF
    chmod 600 "${env_file}"

    cat >"${fake_bin}/openssl" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$@" >"${AGENTFORGE_TEST_OPENSSL_LOG}"
key_path=''
cert_path=''
while (($#)); do
    case "$1" in
        -keyout) key_path="$2"; shift 2 ;;
        -out) cert_path="$2"; shift 2 ;;
        *) shift ;;
    esac
done
mkdir -p "$(dirname "${key_path}")" "$(dirname "${cert_path}")"
printf 'bootstrap key\n' >"${key_path}"
printf 'bootstrap certificate\n' >"${cert_path}"
EOF
    chmod +x "${fake_bin}/openssl"

    PATH="${fake_bin}:${PATH}" \
    AGENTFORGE_REPO_DIR="${REPO_UNDER_TEST}" \
    AGENTFORGE_ENV_FILE="${env_file}" \
    AGENTFORGE_TLS_ROOT="${tls_root}" \
    AGENTFORGE_TEST_OPENSSL_LOG="${openssl_log}" \
        "${REPO_UNDER_TEST}/scripts/deploy/init-tls.sh"

    assert_line 'subjectAltName=DNS:example.com,DNS:www.example.com' "${openssl_log}"
    [[ -s "${tls_root}/current/fullchain.pem" && -s "${tls_root}/current/privkey.pem" ]] ||
        fail 'domain bootstrap certificate was not written to the configured current directory'
}

run_domain_environment_validation() {
    local scenario_root="${TEST_ROOT}/validate-domain"
    local env_file="${scenario_root}/agentforge.env"
    mkdir -p "${scenario_root}"
    cat >"${env_file}" <<'EOF'
PUBLIC_HOST=example.com
POSTGRES_DB=agentforge
POSTGRES_USER=agentforge
POSTGRES_PASSWORD=aaaaaaaaaaaaaaaaaaaaaaaa
AGENTFORGE_JWT_SECRET=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
AGENTFORGE_AGENT_INTERNAL_TOKEN=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
AGENTFORGE_CORE_INTERNAL_TOKEN=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
AGENTFORGE_REGISTRATION_ENABLED=false
AGENTFORGE_AI_DAILY_LIMIT=30
AGENTFORGE_DEMO_FIXED_EMAIL=210168y@gmail.com
AGENTFORGE_DEMO_FIXED_PASSWORD=Z1060168
AGENTFORGE_AGENT_LLM_PROVIDER=disabled
EOF
    chmod 600 "${env_file}"

    validation_output="$(
        AGENTFORGE_REPO_DIR="${REPO_UNDER_TEST}" \
        AGENTFORGE_ENV_FILE="${env_file}" \
            "${REPO_UNDER_TEST}/scripts/deploy/validate-env.sh"
    )"
    [[ "${validation_output}" == *'Production environment validation passed.'* ]] ||
        fail 'valid root-domain production environment was rejected'
}

run_domain_environment_generation() {
    local scenario_root="${TEST_ROOT}/generate-domain"
    local env_file="${scenario_root}/generated.env"
    local fake_bin="${scenario_root}/bin"
    mkdir -p "${fake_bin}"
    cat >"${fake_bin}/openssl" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
case "$*" in
    'rand -hex 24') printf '%048d\n' 0 ;;
    'rand -base64 48') printf 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n' ;;
    'rand -hex 32') printf '%064d\n' 0 ;;
    *) echo "Unexpected openssl test arguments: $*" >&2; exit 1 ;;
esac
EOF
    chmod +x "${fake_bin}/openssl"

    PATH="${fake_bin}:${PATH}" \
    AGENTFORGE_ENV_FILE="${env_file}" \
        "${REPO_UNDER_TEST}/scripts/deploy/generate-production-env.sh" example.com disabled >/dev/null

    assert_line 'PUBLIC_HOST=example.com' "${env_file}"
    assert_line 'AGENTFORGE_JWT_ISSUER=https://example.com/core-api' "${env_file}"
    [[ "$(stat -c '%a' "${env_file}")" == '600' ]] || fail 'generated domain environment must use mode 600'

    local ipv6_env_file="${scenario_root}/generated-ipv6.env"
    PATH="${fake_bin}:${PATH}" \
    AGENTFORGE_ENV_FILE="${ipv6_env_file}" \
        "${REPO_UNDER_TEST}/scripts/deploy/generate-production-env.sh" 2001:db8::10 disabled >/dev/null

    assert_line 'PUBLIC_HOST=2001:db8::10' "${ipv6_env_file}"
    assert_line 'AGENTFORGE_JWT_ISSUER=https://[2001:db8::10]/core-api' "${ipv6_env_file}"
}

run_invalid_public_host_rejection() {
    local scenario_root="${TEST_ROOT}/invalid-host"
    local env_file="${scenario_root}/agentforge.env"
    local fake_bin="${scenario_root}/bin"
    mkdir -p "${fake_bin}"
    cat >"${env_file}" <<'EOF'
PUBLIC_HOST=https://example.com
POSTGRES_DB=agentforge
POSTGRES_USER=agentforge
LETSENCRYPT_EMAIL=
EOF
    chmod 600 "${env_file}"
    cat >"${fake_bin}/docker" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
    chmod +x "${fake_bin}/docker"

    set +e
    rejection_output="$(
        PATH="${fake_bin}:${PATH}" \
        AGENTFORGE_REPO_DIR="${REPO_UNDER_TEST}" \
        AGENTFORGE_ENV_FILE="${env_file}" \
        AGENTFORGE_TLS_ROOT="${scenario_root}/tls" \
            "${REPO_UNDER_TEST}/scripts/deploy/tls-issue.sh" 2>&1
    )"
    rejection_status=$?
    set -e
    [[ ${rejection_status} -ne 0 && "${rejection_output}" == *'valid IPv4, IPv6, or DNS domain'* ]] ||
        fail 'TLS entrypoint did not reject a PUBLIC_HOST containing a URL scheme'

    sed -i 's#^PUBLIC_HOST=.*#PUBLIC_HOST=1::2::3#' "${env_file}"
    set +e
    rejection_output="$(
        PATH="${fake_bin}:${PATH}" \
        AGENTFORGE_REPO_DIR="${REPO_UNDER_TEST}" \
        AGENTFORGE_ENV_FILE="${env_file}" \
        AGENTFORGE_TLS_ROOT="${scenario_root}/tls" \
            "${REPO_UNDER_TEST}/scripts/deploy/tls-issue.sh" 2>&1
    )"
    rejection_status=$?
    set -e
    [[ ${rejection_status} -ne 0 && "${rejection_output}" == *'valid IPv4, IPv6, or DNS domain'* ]] ||
        fail 'TLS entrypoint did not reject a malformed IPv6 PUBLIC_HOST'
}

run_ipv4_issue_and_sync
run_root_domain_issue_and_sync
run_www_domain_issue_and_sync
run_ipv6_issue_and_sync
run_root_domain_bootstrap_certificate
run_domain_environment_validation
run_domain_environment_generation
run_invalid_public_host_rejection
echo 'TLS public host contract passed: IPv4/IPv6/domain issue, renewal, bootstrap, validation, generation, and rejection.'
