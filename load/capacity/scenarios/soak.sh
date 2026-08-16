#!/usr/bin/env bash
# Extended-duration moderate load beyond steady's 15-minute window, to catch resource drift
# (heap growth, connection/pool leaks, backlog creep) that a short run wouldn't surface. Duration
# and rate are engineering judgment (not an AD-006 number) — see manifest.yaml#scenarios.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

PROFILE="$1"
run_k6_scenario "$PROFILE" soak 133 10m 210 610 80
log "soak (${PROFILE}): done"
