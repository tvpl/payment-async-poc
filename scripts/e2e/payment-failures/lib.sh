#!/usr/bin/env bash
# Shared helpers for the payment multi-instance failure matrix (T55).
# Sourced by run.sh and every scenarios/*.sh file — never executed directly.

API_KEY="${API_KEY:-dev-key-change-me}"
SANDBOX_NETWORK="${SANDBOX_NETWORK:-payment-sandbox}"
REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"

PASS_COUNT=0
FAIL_COUNT=0
FAILED_SCENARIOS=()

json_field() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$1" | jq -r ".${2} // empty"
  else
    printf '%s' "$1" | grep -oE "\"${2}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
      | head -1 | sed -E "s/.*:[[:space:]]*\"([^\"]*)\"/\1/"
  fi
}

# submit_payment <base_url> [idempotency_key] -> prints "HTTP_STATUS\nBODY"
submit_payment() {
  local base_url="$1" idem="${2:-scn-$(date +%s)-$RANDOM}"
  local body_file http_status
  body_file=$(mktemp)
  # 130s covers the Kafka producer's default delivery.timeout.ms (120s) plus margin — several
  # dependency-outage scenarios legitimately take that long to fail closed. Normal-path calls
  # still return in well under a second; this only raises the ceiling for the slow ones.
  http_status=$(curl -s -o "$body_file" -w '%{http_code}' -m 130 \
    -X POST "${base_url}/payment-simulations" \
    -H 'Content-Type: application/json' \
    -H "X-API-Key: ${API_KEY}" \
    -H "Idempotency-Key: ${idem}" \
    -d '{"merchantId":"MERCHANT-001","amount":125.50,"currency":"BRL","paymentMethod":"CREDIT_CARD","brand":"VISA","installments":3,"captureMode":"AUTHORIZE_AND_CAPTURE"}')
  printf '%s\n' "$http_status"
  cat "$body_file"
  rm -f "$body_file"
}

# poll_terminal <base_url> <request_id> <max_polls> -> prints final status ("" if never terminal)
poll_terminal() {
  local base_url="$1" request_id="$2" max_polls="${3:-20}"
  local body poll_status
  for _ in $(seq 1 "$max_polls"); do
    body=$(curl -s -m 10 -H "X-API-Key: ${API_KEY}" "${base_url}/payment-simulations/${request_id}")
    poll_status=$(json_field "$body" status)
    case "$poll_status" in
      COMPLETED|FAILED|TIMEOUT) echo "$poll_status"; return 0 ;;
    esac
    sleep 1
  done
  echo ""
}

wait_healthy() {
  local container="$1" max_tries="${2:-20}"
  for _ in $(seq 1 "$max_tries"); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)" = "healthy" ] && return 0
    sleep 2
  done
  echo "!! ${container} did not become healthy in time" >&2
  return 1
}

psql_sandbox() {
  docker exec -i payment-sandbox-postgres-1 psql -U sbus -d sbus -tA -c "$1"
}

log_pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  echo "PASS: $1"
}

log_fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  FAILED_SCENARIOS+=("$1")
  echo "FAIL: $1 — $2" >&2
}

require() {
  # require <scenario_name> <condition_description> <bash_test...>
  local name="$1" desc="$2"
  shift 2
  if "$@"; then
    log_pass "${name}: ${desc}"
  else
    log_fail "${name}" "${desc}"
  fi
}
