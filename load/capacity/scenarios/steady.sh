#!/usr/bin/env bash
# CAP-02 (AD-007/AUD-30): 1,000 req/min (17 req/s) sustained for 15 minutes across >=2 tenant API
# keys, zero silent loss, technical error rate < 0.1%, 429 <= 1% of the steady window, avg latency
# <= 300ms, p99 <= 10s. Also exercises CAP-05 (round-robin across payment-api-1/2, requestId
# ordering holds since a duplicate Idempotency-Key sent to both instances mid-run resolves to one
# id).
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

PROFILE="$1"
OUT="${REPORTS_DIR}/${PROFILE}"
mkdir -p "$OUT"

# CAP-05 cross-instance probe: same Idempotency-Key sent concurrently to both API instances
# must resolve to the same requestId (T55 already proved this at rest; this repeats it under
# concurrent load to confirm the guarantee still holds while the fleet is busy).
DUP_KEY="cap05-steady-$(date +%s)"
( curl -s -o /tmp/cap05_a.json -X POST "http://localhost:${API_HOST_PORT_1}/payment-simulations" \
    -H 'Content-Type: application/json' -H "X-API-Key: ${PAYMENT_API_KEY}" -H "Idempotency-Key: ${DUP_KEY}" \
    -d '{"merchantId":"MERCHANT-001","amount":10.00,"currency":"BRL","paymentMethod":"CREDIT_CARD","brand":"VISA","installments":1,"captureMode":"AUTHORIZE_AND_CAPTURE"}' ) &
( curl -s -o /tmp/cap05_b.json -X POST "http://localhost:${API_HOST_PORT_2}/payment-simulations" \
    -H 'Content-Type: application/json' -H "X-API-Key: ${PAYMENT_API_KEY}" -H "Idempotency-Key: ${DUP_KEY}" \
    -d '{"merchantId":"MERCHANT-001","amount":10.00,"currency":"BRL","paymentMethod":"CREDIT_CARD","brand":"VISA","installments":1,"captureMode":"AUTHORIZE_AND_CAPTURE"}' ) &
wait
REQ_A=$(jq -r '.requestId // empty' /tmp/cap05_a.json 2>/dev/null)
REQ_B=$(jq -r '.requestId // empty' /tmp/cap05_b.json 2>/dev/null)
if [ -n "$REQ_A" ] && [ "$REQ_A" = "$REQ_B" ]; then
  log "CAP-05 duplicate-key probe: PASS (requestId=${REQ_A} on both instances)"
else
  log "CAP-05 duplicate-key probe: FAIL (a=${REQ_A} b=${REQ_B})"
fi
echo "{\"requestId_a\":\"${REQ_A}\",\"requestId_b\":\"${REQ_B}\",\"match\":$( [ "$REQ_A" = "$REQ_B" ] && [ -n "$REQ_A" ] && echo true || echo false )}" \
  > "${OUT}/steady.cap05_probe.json"

run_k6_scenario "$PROFILE" steady 17 15m 100 300 100
log "steady (${PROFILE}): done"
