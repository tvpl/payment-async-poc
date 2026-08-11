#!/usr/bin/env bash
# Cross-boundary release gate for the workspace (Phase 9, T54-T60).
#
# Each stage below corresponds to one design.md §8.2 step. Stages are additive: later tasks in
# Phase 9 (T55 failure matrices, T57 capacity, T58 observability, T60 release evidence) add their
# own stage rather than replacing this one, so this script grows into the full release gate that
# T60 records evidence from. A stage that has no implementation yet is simply not called here.
#
#   scripts/verify-workspace.sh                 # run every stage
#   scripts/verify-workspace.sh equivalence      # run one stage
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

STAGE="${1:-all}"

stage_equivalence() {
  echo "=== equivalence: inventory has not lost a valid item ==="
  python3 scripts/equivalence/equivalence.py verify \
    --root . --manifest scripts/equivalence/baseline-manifest.json
}

stage_no_composite_build() {
  echo "=== no-composite-build: consumers resolve cross-boundary deps by GAV, not sibling source ==="
  python3 scripts/e2e/check_no_composite_build.py --root .
}

stage_artifact_only_fixture() {
  echo "=== artifact-only-consumer: published GAV resolves, missing GAV fails (generic fixture) ==="
  scripts/artifacts/verify-artifact-only.sh
}

stage_e2e_payment() {
  echo "=== e2e: payment flow (API -> Kafka -> SBUS -> core-mock) ==="
  scripts/smoke.sh
}

stage_e2e_async_redis() {
  echo "=== e2e: async-redis flow (submit -> Redis Stream -> worker -> BRPOP wakeup) ==="
  scripts/e2e/async_redis_smoke.sh
}

stage_payment_failures() {
  echo "=== e2e: payment multi-instance failure matrix (PAY-05..09, CAP-05, CAP-06) ==="
  # sandbox/.env is gitignored/local-only; source it if present so POSTGRES_PASSWORD doesn't
  # have to be passed by hand on every invocation.
  [ -f sandbox/.env ] && set -a && source sandbox/.env && set +a
  scripts/e2e/payment-failures/run.sh
}

stage_hygiene() {
  echo "=== hygiene: git diff --check ==="
  git diff --check
}

case "$STAGE" in
  equivalence) stage_equivalence ;;
  no-composite-build) stage_no_composite_build ;;
  artifact-only-fixture) stage_artifact_only_fixture ;;
  e2e-payment) stage_e2e_payment ;;
  e2e-async-redis) stage_e2e_async_redis ;;
  payment-failures) stage_payment_failures ;;
  hygiene) stage_hygiene ;;
  all)
    stage_equivalence
    stage_no_composite_build
    stage_artifact_only_fixture
    stage_e2e_payment
    stage_e2e_async_redis
    stage_payment_failures
    stage_hygiene
    ;;
  *)
    echo "unknown stage: ${STAGE}" >&2
    exit 1
    ;;
esac

echo "verify-workspace: PASS (stage=${STAGE})"
