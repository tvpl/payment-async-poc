#!/usr/bin/env bash
# CAP-03: 20,000 req/min (333 req/s) burst for 60s immediately after steady — proves bounded
# buffering/429/202 rather than unbounded growth of memory, connections, PEL, or outbox.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

PROFILE="$1"
run_k6_scenario "$PROFILE" spike 333 60s 520 1500 50
log "spike (${PROFILE}): done"
