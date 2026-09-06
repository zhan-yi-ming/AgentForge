#!/usr/bin/env bash
set -Eeuo pipefail
exec "$(dirname "${BASH_SOURCE[0]}")/seed-demo-v12.sh" "$@"
