#!/usr/bin/env bash
# End-to-end smoke test for the Payment Simulation sandbox.
#
# Sends one POST /payment-simulations, then polls GET /payment-simulations/{requestId}
# until a terminal state (COMPLETED / FAILED / TIMEOUT). Proves the whole pipeline works:
# API -> Kafka -> SBUS (+ outbox) -> core-mock -> back to the API.
#
#   ./scripts/smoke.sh
#   BASE_URL=http://localhost:8080 API_KEY=dev-key-change-me ./scripts/smoke.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-dev-key-change-me}"
MAX_POLLS="${MAX_POLLS:-20}"
IDEMPOTENCY_KEY="smoke-$(date +%s)-$RANDOM"

# Extract a top-level JSON string field without requiring jq.
json_field() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$1" | jq -r ".${2} // empty"
  else
    printf '%s' "$1" | grep -oE "\"${2}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
      | head -1 | sed -E "s/.*:[[:space:]]*\"([^\"]*)\"/\1/"
  fi
}

PAYLOAD='{"merchantId":"MERCHANT-001","amount":125.50,"currency":"BRL",
  "paymentMethod":"CREDIT_CARD","brand":"VISA","installments":3,
  "captureMode":"AUTHORIZE_AND_CAPTURE"}'

echo "==> POST ${BASE_URL}/payment-simulations (Idempotency-Key: ${IDEMPOTENCY_KEY})"
HTTP_BODY=$(mktemp)
trap 'rm -f "$HTTP_BODY"' EXIT
STATUS_CODE=$(curl -s -o "$HTTP_BODY" -w '%{http_code}' \
  -X POST "${BASE_URL}/payment-simulations" \
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

REQUEST_ID=$(json_field "$BODY" requestId)
if [ -z "$REQUEST_ID" ]; then
  echo "!! Could not parse requestId from the response." >&2
  exit 1
fi

STATUS=$(json_field "$BODY" status)
echo "==> requestId=${REQUEST_ID} initial status=${STATUS}"

# If the Core answered within the wait-timeout, the POST already carries a terminal state.
case "$STATUS" in
  COMPLETED|FAILED|TIMEOUT)
    echo "==> Terminal on POST: ${STATUS}"
    echo "SMOKE OK (${STATUS})"
    exit 0
    ;;
esac

echo "==> Polling status until terminal (max ${MAX_POLLS} tries)..."
for i in $(seq 1 "$MAX_POLLS"); do
  sleep 1
  GET_BODY=$(curl -s -H "X-API-Key: ${API_KEY}" "${BASE_URL}/payment-simulations/${REQUEST_ID}")
  STATUS=$(json_field "$GET_BODY" status)
  echo "    [${i}] status=${STATUS}"
  case "$STATUS" in
    COMPLETED|FAILED|TIMEOUT)
      echo "==> Final: ${GET_BODY}"
      echo "SMOKE OK (${STATUS})"
      exit 0
      ;;
  esac
done

echo "!! Did not reach a terminal state within ${MAX_POLLS}s." >&2
exit 1
