#!/usr/bin/env bash
# EDG-04/DOC-03/design.md §7.1: logs SHALL propagate requestId, correlationId, causationId and
# trace id. Submits one real request and inspects the real structured (JSON) log lines each
# service emitted for it.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

RESP=$(submit_payment "obs-corr-$(date +%s)-$RANDOM")
STATUS=$(echo "$RESP" | head -1)
BODY=$(echo "$RESP" | tail -n +2)
REQUEST_ID=$(echo "$BODY" | jq -r '.requestId // empty')

if [ -z "$REQUEST_ID" ]; then
  check_fail "correlation-ids" "submit_payment did not return a requestId (status=${STATUS}, body=${BODY})"
  return 0
fi

# Give the async pipeline (Kafka -> SBUS -> Core -> SBUS -> API) time to fully process and log.
sleep 4

api_line=$(docker logs payment-api-api-1 2>&1 | grep "\"requestId\":\"${REQUEST_ID}\"" | tail -1)
sbus_line=$(docker logs payment-sbus-sbus-1 2>&1 | grep "\"requestId\":\"${REQUEST_ID}\"" | tail -1)

# --- payment-api: requestId, correlationId, traceId (ApiPaymentService.java:113-115,
#     PaymentResponseConsumer.java:146-150 — neither sets causationId, see below) ---
if [ -n "$api_line" ] \
  && echo "$api_line" | jq -e '.requestId and .correlationId and .traceId' >/dev/null 2>&1; then
  check_pass "payment-api log carries requestId+correlationId+traceId for ${REQUEST_ID}"
else
  check_fail "payment-api log carries requestId+correlationId+traceId" "no matching log line with all three fields (line=${api_line:-none})"
fi

# --- payment-sbus: requestId, correlationId, causationId, traceId (Mdc.java:14-20) ---
if [ -n "$sbus_line" ] \
  && echo "$sbus_line" | jq -e '.requestId and .correlationId and .causationId and .traceId' >/dev/null 2>&1; then
  check_pass "payment-sbus log carries requestId+correlationId+causationId+traceId for ${REQUEST_ID}"
else
  check_fail "payment-sbus log carries requestId+correlationId+causationId+traceId" "no matching log line with all four fields (line=${sbus_line:-none})"
fi

# --- known gap, asserted honestly (not skipped): payment-api never populates causationId in
#     its own MDC (ApiPaymentService.java:113-115, PaymentResponseConsumer.java:146-150), even
#     though EventEnvelope carries it (common/.../EventEnvelope.java:29) and logback.xml
#     declares it as an expected field. This assertion targets the DOCUMENTED guarantee
#     (design.md §7.1), not current behavior — it is expected to fail until the gap is fixed.
if [ -n "$api_line" ] && echo "$api_line" | jq -e '.causationId' >/dev/null 2>&1; then
  check_pass "payment-api log carries causationId for ${REQUEST_ID}"
else
  check_fail "payment-api log carries causationId (design.md §7.1)" \
    "payment-api never puts causationId into MDC (ApiPaymentService.java:113-115, PaymentResponseConsumer.java:146-150) — real gap, tracked as follow-up"
fi
