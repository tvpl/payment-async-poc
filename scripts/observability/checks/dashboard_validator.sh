#!/usr/bin/env bash
# EDG-04: "IF um dashboard depender de métrica removida THEN observability validation SHALL
# detectar a referência antes de excluir a implementação antiga." T5 built the detector
# (scripts/docs/validate_docs.py#dashboard_metric_errors); this proves it end-to-end with a real
# fixture file (T5's own test only unit-tests the string-matching helper, not a full dashboard
# JSON on disk), then proves it doesn't false-positive on the real dashboards.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

DASHBOARDS_DIR="${REPO_ROOT}/sandbox/observability/dashboards"
FIXTURE="${DASHBOARDS_DIR}/__t58_scratch_broken_dashboard.json"

cleanup() { rm -f "$FIXTURE"; }
trap cleanup EXIT

run_validator() {
  PYTHONPATH="${REPO_ROOT}/scripts/docs" python3 -c "
import sys
from pathlib import Path
from validate_docs import dashboard_metric_errors
for e in dashboard_metric_errors(Path('${REPO_ROOT}')):
    print(e)
"
}

# --- baseline: real dashboards, no fixture, must be clean ---
baseline_errors=$(run_validator)
if [ -z "$baseline_errors" ]; then
  check_pass "real dashboards under */ops/dashboards, sandbox/observability/dashboards and load/dashboards reference only implemented metrics"
else
  check_fail "real dashboards under */ops/dashboards, sandbox/observability/dashboards and load/dashboards reference only implemented metrics" \
    "unexpected pre-existing errors: ${baseline_errors}"
fi

# --- inject a dashboard referencing a metric that provably does not exist anywhere ---
cat > "$FIXTURE" <<'EOF'
{
  "title": "T58 scratch fixture (deleted immediately after this check)",
  "panels": [
    { "targets": [ { "expr": "this_metric_was_removed_and_must_be_detected_t58" } ] }
  ]
}
EOF

fixture_errors=$(run_validator)
cleanup
trap - EXIT

if echo "$fixture_errors" | grep -q "this_metric_was_removed_and_must_be_detected_t58"; then
  check_pass "validator detects a dashboard referencing a removed/nonexistent metric (EDG-04)"
else
  check_fail "validator detects a dashboard referencing a removed/nonexistent metric (EDG-04)" \
    "injected fixture was not flagged: ${fixture_errors:-<no errors reported>}"
fi

# Prove the scratch file left no trace (mirrors the Verifier's discrimination-sensor discipline:
# inject in a scratch state, discard, confirm the real tree is back to its prior porcelain).
if [ -f "$FIXTURE" ]; then
  check_fail "scratch fixture cleaned up" "fixture file still present at ${FIXTURE}"
else
  check_pass "scratch fixture cleaned up, real tree unchanged"
fi
