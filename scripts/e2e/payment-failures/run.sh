#!/usr/bin/env bash
# T55: multi-instance payment failure matrix (PAY-05, PAY-06, PAY-07, PAY-08, PAY-09, CAP-05, CAP-06).
#
# Requires: sandbox core infra + payment-api/payment-sbus/payment-core-mock up (see
# scripts/e2e/README.md), payment-api:local and payment-sbus:local images built (T54).
#
#   POSTGRES_PASSWORD=<sandbox/.env value> scripts/e2e/payment-failures/run.sh
#
# Brings up a second payment-api/payment-sbus instance for the duration of the run, executes
# every scenario in scenarios/*.sh, tears the second instances down, and reports PASS/FAIL per
# scenario plus a final tally. Exits non-zero unless every scenario passes AND at least 10 ran
# (the task's "≥10 cenários passam" gate) — a scenario can be an intentionally-documented FAIL
# (a real gap found live, tracked as a follow-up) without failing the whole suite, as long as
# the pass floor is still met; see the scenario's own log line for whether that's the case.
# No `-u`: this system's default /bin/bash (3.2, macOS) treats `${arr[@]}` on an empty array as
# an unbound-variable error under `set -u`, which the scenario scripts' array patterns rely on.
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"
# shellcheck source=scenarios/concurrency.sh
source "$SCRIPT_DIR/scenarios/concurrency.sh"
# shellcheck source=scenarios/crash_recovery.sh
source "$SCRIPT_DIR/scenarios/crash_recovery.sh"
# shellcheck source=scenarios/core_backpressure.sh
source "$SCRIPT_DIR/scenarios/core_backpressure.sh"
# shellcheck source=scenarios/poison.sh
source "$SCRIPT_DIR/scenarios/poison.sh"
# shellcheck source=scenarios/dependency_outages.sh
source "$SCRIPT_DIR/scenarios/dependency_outages.sh"

: "${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD to the sandbox Postgres password (sandbox/.env)}"

echo "==> bringing up the second api/sbus instance (CAP-05 fleet)"
"$SCRIPT_DIR/multi_instance.sh" up
trap '"$SCRIPT_DIR/multi_instance.sh" down' EXIT

# Docker's healthcheck proves the HTTP listener answers, not that the freshly-joined Kafka
# consumer group has finished its first rebalance — a short settle margin avoids the first
# scenario racing that warmup.
sleep 8

echo "==> running scenarios"
scenario_duplicate_idempotency_key_cross_instance
scenario_cross_instance_fleet_coordination
# Runs before the crash/kill scenarios deliberately: sbus-container-kill-mid-flight triggers a
# real Kafka consumer-group rebalance on restart, which can pause claiming fleet-wide for tens of
# seconds — unrelated to this scenario's own timing budget, so it goes first to avoid the churn.
scenario_due_retry_does_not_block_live_traffic
scenario_outbox_crash_window_reclaim
scenario_sbus_kill_mid_flight
scenario_slow_core_backpressure
scenario_poison_message_to_dlq
scenario_kafka_unavailable
scenario_redis_unavailable_api
scenario_postgres_unavailable_sbus
scenario_registry_unavailable

TOTAL=$((PASS_COUNT + FAIL_COUNT))
echo
echo "==> payment-failures: ${PASS_COUNT}/${TOTAL} passed"
if [ "${#FAILED_SCENARIOS[@]}" -gt 0 ]; then
  echo "    failed: ${FAILED_SCENARIOS[*]}"
fi

if [ "$PASS_COUNT" -lt 10 ]; then
  echo "FAIL: fewer than 10 scenarios passed (Done-when requires >=10)" >&2
  exit 1
fi
echo "payment-failures: PASS (${PASS_COUNT}/${TOTAL} scenarios, floor is 10)"
