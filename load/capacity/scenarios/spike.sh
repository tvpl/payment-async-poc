#!/usr/bin/env bash
# CAP-03 (AD-007/AUD-30): 2,000 req/min (33 req/s) burst for 60s immediately after steady, across
# >=2 tenant API keys — proves bounded buffering/429/202 rather than unbounded growth of memory,
# connections, PEL, or outbox.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

PROFILE="$1"
run_k6_scenario "$PROFILE" spike 33 60s 150 500 50
log "spike (${PROFILE}): done"
