#!/usr/bin/env bash
# T56: async-redis multi-instance failure matrix (RED-01 through RED-08).
#
# Requires: sandbox core infra + async-redis-service up (see scripts/e2e/README.md),
# async-redis-service:local image built (T54).
#
#   scripts/e2e/async-redis-failures/run.sh
#
# Brings up a second async-redis-service instance for the RED-04 identity/reclaim scenario, runs
# every scenario in scenarios/*.sh (most bring up and tear down their own short-lived scratch
# containers with scenario-specific config — pool size, TTLs, latency, max-deliveries, prod env —
# since the 8 requirements need mutually incompatible tuning), tears the shared second instance
# down, and reports PASS/FAIL per assertion plus a final tally. Exits non-zero unless at least 10
# assertions pass (this task's "≥10 cenários" floor — 10 named scenario functions below, each
# contributing 2-6 assertions, so the real margin is well above the floor).
#
# No `-u`: this system's default /bin/bash (3.2, macOS) treats `${arr[@]}` on an empty array as
# an unbound-variable error under `set -u`, same as payment-failures/run.sh.
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"
# shellcheck source=scenarios/status_lifecycle.sh
source "$SCRIPT_DIR/scenarios/status_lifecycle.sh"
# shellcheck source=scenarios/backpressure.sh
source "$SCRIPT_DIR/scenarios/backpressure.sh"
# shellcheck source=scenarios/retention.sh
source "$SCRIPT_DIR/scenarios/retention.sh"
# shellcheck source=scenarios/identity.sh
source "$SCRIPT_DIR/scenarios/identity.sh"
# shellcheck source=scenarios/redis_outage.sh
source "$SCRIPT_DIR/scenarios/redis_outage.sh"
# shellcheck source=scenarios/atomic_release.sh
source "$SCRIPT_DIR/scenarios/atomic_release.sh"
# shellcheck source=scenarios/dlq.sh
source "$SCRIPT_DIR/scenarios/dlq.sh"
# shellcheck source=scenarios/rate_limit.sh
source "$SCRIPT_DIR/scenarios/rate_limit.sh"
# shellcheck source=scenarios/production_guard.sh
source "$SCRIPT_DIR/scenarios/production_guard.sh"

echo "==> bringing up async-redis-2 (RED-04 fleet)"
"$SCRIPT_DIR/multi_instance.sh" up
trap '"$SCRIPT_DIR/multi_instance.sh" down' EXIT

sleep 3

echo "==> running scenarios"
scenario_status_lifecycle_all_states
scenario_wait_pool_backpressure
scenario_retention_alert_without_autotrim
scenario_cross_instance_worker_identity
scenario_redis_outage_worker_readiness
scenario_atomic_release_survives_ownership_theft
scenario_malformed_message_dlq_before_ack
scenario_exceeded_deliveries_dlq
scenario_admission_limit_shared_across_instances
scenario_production_guard_rejects_insecure_config

TOTAL=$((PASS_COUNT + FAIL_COUNT))
echo
echo "==> async-redis-failures: ${PASS_COUNT}/${TOTAL} assertions passed (10 scenarios)"
if [ "${#FAILED_SCENARIOS[@]}" -gt 0 ]; then
  echo "    failed: ${FAILED_SCENARIOS[*]}"
fi

if [ "$PASS_COUNT" -lt 10 ]; then
  echo "FAIL: fewer than 10 assertions passed (Done-when requires >=10)" >&2
  exit 1
fi
if [ "$FAIL_COUNT" -gt 0 ]; then
  echo "FAIL: ${FAIL_COUNT} assertion(s) failed" >&2
  exit 1
fi
echo "async-redis-failures: PASS (${PASS_COUNT}/${TOTAL} assertions, floor is 10)"
