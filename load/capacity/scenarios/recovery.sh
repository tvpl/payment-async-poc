#!/usr/bin/env bash
# CAP-06: no new load — samples resource signals every 10s for 3 minutes to measure drain time
# (how long the waiter pool / outbox backlog / DLQ take to return to idle once load stops).
# Immediately follows spike (certified-target) or slowdown (both profiles), where backlog was
# deliberately built up.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

PROFILE="$1"
OUT="${REPORTS_DIR}/${PROFILE}"
mkdir -p "$OUT"
TIMELINE="${OUT}/recovery.timeline.jsonl"
: > "$TIMELINE"

SAMPLES=18 # 18 x 10s = 3 minutes
[ "${DRYRUN:-0}" = "1" ] && SAMPLES=3
log "recovery (${PROFILE}): observing drain for ${SAMPLES} x 10s"
for i in $(seq 1 "$SAMPLES"); do
  "${CAPACITY_DIR}/collect_metrics.sh" "t${i}" >> "$TIMELINE"
  sleep 10
done
log "recovery (${PROFILE}): done, timeline at ${TIMELINE}"
