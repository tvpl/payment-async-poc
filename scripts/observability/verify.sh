#!/usr/bin/env bash
# T58: validates observability end to end against real containers — logs propagate
# request/correlation/causation/trace ids, traces reach Jaeger, the sandbox's own Prometheus
# scrapes an application-owned target, critical alerts fire on a real triggering condition, and
# the dashboard-metric validator (T5) actually catches a broken dashboard. Requires sandbox infra
# with the observability profile up (`cd sandbox && make up-all`).
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

log "bringing up single-instance fleet (payment-api, payment-sbus, payment-core-mock)"
if ! bring_up; then
  log "!! fleet bring-up failed"
  exit 1
fi

for check in correlation_ids trace_in_jaeger metrics_scrape assets_manifest dashboard_validator alert_fires; do
  log "=== ${check} ==="
  # Sourced, not run as a subprocess: check_pass/check_fail update PASS_COUNT/FAIL_COUNT/
  # FAILED_CHECKS in THIS shell, so the final tally below sees every check's contribution
  # instead of each running in its own throwaway process.
  # shellcheck disable=SC1090
  source "${SCRIPT_DIR}/checks/${check}.sh"
done

log "tearing down fleet"
down

echo ""
log "RESULT: ${PASS_COUNT} passed, ${FAIL_COUNT} failed"
if [ ${#FAILED_CHECKS[@]} -gt 0 ]; then
  log "failed checks:"
  for f in "${FAILED_CHECKS[@]}"; do log "  - ${f}"; done
fi

if [ "$PASS_COUNT" -ge 8 ]; then
  log "floor met (>=8 passed)"
  exit 0
else
  log "floor NOT met (<8 passed)"
  exit 1
fi
