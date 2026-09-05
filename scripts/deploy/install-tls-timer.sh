#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "${EUID}" -ne 0 ]]; then
    echo "Run this command as root." >&2
    exit 1
fi
install -m 0644 "$(dirname "${BASH_SOURCE[0]}")/systemd/agentforge-tls-renew.service" /etc/systemd/system/
install -m 0644 "$(dirname "${BASH_SOURCE[0]}")/systemd/agentforge-tls-renew.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now agentforge-tls-renew.timer
systemctl list-timers agentforge-tls-renew.timer --no-pager
