#!/usr/bin/env bash
# Dependency-slowdown leg of the performance gate (design.md §8.3): injects a transient Core
# latency spike (1.5-3.5s, above payment-api's 3s wait-timeout) mid-run, then restores Core to
# its profile baseline. Proves the admission/fallback path degrades gracefully (202 fallback,
# bounded waiter pool) under a slow dependency rather than cascading.
#
# Offered rate is deliberately low (2 req/s), not the scenario's usual rate: core-mock's
# single-partition/SYNC_PER_RECORD consumer (manifest.yaml#app_config.payment-core-mock) makes
# per-message latency directly the throughput ceiling, so a 1.5-3.5s injected latency caps Core
# at ~0.3-0.7 msg/s regardless of profile. Offering 100 req/s into that (the first attempt) built
# a backlog of ~24,000 messages in 4 minutes — recoverable in theory, but not inside this gate's
# time budget (draining it at the same capped rate would take hours). 2 req/s over 90s offers
# ~180 requests, of which Core can actually clear ~50-60 during the window; the rest (~120-130)
# drain in well under a second once recovery.sh restores full-speed Core, which is what actually
# demonstrates CAP-06's "recuperação mensurável" instead of a backlog recovery.sh's 3-minute
# window could never observe finishing.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

PROFILE="$1"
case "$PROFILE" in
  certified-target) RESTORE_MIN=1; RESTORE_MAX=2 ;;
  constrained-core) RESTORE_MIN=20; RESTORE_MAX=20 ;;
esac

restore_core() {
  log "slowdown (${PROFILE}): restoring core-mock latency to ${RESTORE_MIN}-${RESTORE_MAX}ms"
  reconfigure_core "$RESTORE_MIN" "$RESTORE_MAX"
}
trap restore_core EXIT

log "slowdown (${PROFILE}): injecting core-mock latency 1500-3500ms"
reconfigure_core 1500 3500

run_k6_scenario "$PROFILE" slowdown 2 90s 20 40 2
log "slowdown (${PROFILE}): done"
