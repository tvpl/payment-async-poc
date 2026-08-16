#!/usr/bin/env bash
# T57 capacity gate orchestrator. Requires sandbox infra already up (kafka/redis/postgres/
# registry on the payment-sandbox network — `sandbox/Makefile up` or equivalent) and the four
# app images built (`payment-api:local`, `payment-sbus:local`, `payment-core-mock:local`).
#
# Usage: run_gate.sh [profile ...]   (defaults to both: certified-target constrained-core)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

PROFILES=("$@")
[ ${#PROFILES[@]} -eq 0 ] && PROFILES=(certified-target constrained-core)

START_TS=$(date +%s)
mkdir -p "$REPORTS_DIR"

for profile in "${PROFILES[@]}"; do
  log "=== profile: ${profile} — bring-up ==="
  if ! bring_up "$profile"; then
    log "!! bring-up failed for ${profile}, aborting this profile"
    down
    continue
  fi

  warm_up

  "${SCRIPT_DIR}/scenarios/steady.sh" "$profile"
  "${SCRIPT_DIR}/scenarios/spike.sh" "$profile"
  "${SCRIPT_DIR}/scenarios/soak.sh" "$profile"
  "${SCRIPT_DIR}/scenarios/slowdown.sh" "$profile"
  "${SCRIPT_DIR}/scenarios/recovery.sh" "$profile"

  log "=== profile: ${profile} — teardown ==="
  down
done

ELAPSED=$(( $(date +%s) - START_TS ))
log "all profiles done in ${ELAPSED}s — generating report"

REPORT_PATH="${REPORTS_DIR}/$(date -u +%Y%m%d-%H%M%S)-capacity-report.md"
python3 "${CAPACITY_DIR}/generate_report.py" aggregate \
  --reports-dir "$REPORTS_DIR" --manifest "${CAPACITY_DIR}/manifest.yaml" --output "$REPORT_PATH"
REPORT_EXIT=$?

log "report: ${REPORT_PATH} (aggregate exit=${REPORT_EXIT})"
exit "$REPORT_EXIT"
