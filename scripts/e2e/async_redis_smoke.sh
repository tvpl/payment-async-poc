#!/usr/bin/env bash
# End-to-end smoke test for the async-redis-service (Kafka-free async->sync example).
#
# Sends one POST /jobs, then polls GET /jobs/{jobId} until a terminal state (COMPLETED).
# Proves the whole Redis Streams round trip works: submit -> enqueue -> worker -> BRPOP wakeup.
#
#   ./scripts/e2e/async_redis_smoke.sh
#   BASE_URL=http://localhost:8084 API_KEY=dev-key-change-me ./scripts/e2e/async_redis_smoke.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8084}"
API_KEY="${API_KEY:-dev-key-change-me}"
MAX_POLLS="${MAX_POLLS:-20}"
IDEMPOTENCY_KEY="e2e-$(date +%s)-$RANDOM"

# Extract a top-level JSON string field without requiring jq.
json_field() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$1" | jq -r ".${2} // empty"
  else
    printf '%s' "$1" | grep -oE "\"${2}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
      | head -1 | sed -E "s/.*:[[:space:]]*\"([^\"]*)\"/\1/"
  fi
}

PAYLOAD='{"reference":"e2e-order-001","amountCents":12550,"note":"workspace e2e"}'

echo "==> POST ${BASE_URL}/jobs (Idempotency-Key: ${IDEMPOTENCY_KEY})"
HTTP_BODY=$(mktemp)
trap 'rm -f "$HTTP_BODY"' EXIT
STATUS_CODE=$(curl -s -o "$HTTP_BODY" -w '%{http_code}' \
  -X POST "${BASE_URL}/jobs" \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: ${API_KEY}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d "$PAYLOAD")
BODY=$(cat "$HTTP_BODY")

echo "    HTTP ${STATUS_CODE}: ${BODY}"
if [ "$STATUS_CODE" = "401" ]; then
  echo "!! 401 Unauthorized — wrong/missing X-API-Key (API_KEY=${API_KEY})." >&2
  exit 1
fi

JOB_ID=$(json_field "$BODY" jobId)
if [ -z "$JOB_ID" ]; then
  echo "!! Could not parse jobId from the response." >&2
  exit 1
fi

STATUS=$(json_field "$BODY" status)
echo "==> jobId=${JOB_ID} initial status=${STATUS}"

# If the worker released the result within the wait-timeout, the POST already carries COMPLETED.
if [ "$STATUS" = "COMPLETED" ]; then
  echo "==> Terminal on POST: ${STATUS}"
  echo "SMOKE OK (${STATUS})"
  exit 0
fi

echo "==> Polling status until terminal (max ${MAX_POLLS} tries)..."
for i in $(seq 1 "$MAX_POLLS"); do
  sleep 1
  GET_BODY=$(curl -s -H "X-API-Key: ${API_KEY}" "${BASE_URL}/jobs/${JOB_ID}")
  STATUS=$(json_field "$GET_BODY" status)
  echo "    [${i}] status=${STATUS}"
  case "$STATUS" in
    COMPLETED|EXPIRED)
      echo "==> Final: ${GET_BODY}"
      if [ "$STATUS" = "COMPLETED" ]; then
        echo "SMOKE OK (${STATUS})"
        exit 0
      fi
      echo "!! Job expired before completion." >&2
      exit 1
      ;;
  esac
done

echo "!! Did not reach a terminal state within ${MAX_POLLS}s." >&2
exit 1
