#!/usr/bin/env bash
# Shared helpers for the async-redis multi-instance failure matrix (T56).
# Sourced by run.sh and every scenarios/*.sh file — never executed directly.

API_KEY="${API_KEY:-dev-key-change-me}"
SANDBOX_NETWORK="${SANDBOX_NETWORK:-payment-sandbox}"
REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
REDIS_CONTAINER="${REDIS_CONTAINER:-payment-sandbox-redis-1}"

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

redis_cli() {
  docker exec -i "$REDIS_CONTAINER" redis-cli "$@"
}

# submit_job <base_url> [idempotency_key] [reference] -> prints "HTTP_STATUS\nBODY"
submit_job() {
  local base_url="$1" idem="${2:-scn-$(date +%s)-$RANDOM}" reference="${3:-scn-ref-$RANDOM}"
  local body_file http_status
  body_file=$(mktemp)
  http_status=$(curl -s -o "$body_file" -w '%{http_code}' -m 30 \
    -X POST "${base_url}/jobs" \
    -H 'Content-Type: application/json' \
    -H "X-API-Key: ${API_KEY}" \
    -H "Idempotency-Key: ${idem}" \
    -d "{\"reference\":\"${reference}\",\"amountCents\":12550,\"note\":\"t56 failure matrix\"}")
  printf '%s\n' "$http_status"
  cat "$body_file"
  rm -f "$body_file"
}

# get_job <base_url> <job_id> -> prints "HTTP_STATUS\nBODY"
get_job() {
  local base_url="$1" job_id="$2"
  local body_file http_status
  body_file=$(mktemp)
  http_status=$(curl -s -o "$body_file" -w '%{http_code}' -m 10 \
    -H "X-API-Key: ${API_KEY}" "${base_url}/jobs/${job_id}")
  printf '%s\n' "$http_status"
  cat "$body_file"
  rm -f "$body_file"
}

# poll_terminal <base_url> <job_id> <max_polls> -> prints final status ("" if never terminal)
poll_terminal() {
  local base_url="$1" job_id="$2" max_polls="${3:-20}"
  local resp status_code body poll_status
  for _ in $(seq 1 "$max_polls"); do
    resp=$(get_job "$base_url" "$job_id")
    status_code=$(printf '%s' "$resp" | head -1)
    body=$(printf '%s' "$resp" | tail -n +2)
    if [ "$status_code" = "200" ]; then
      echo "COMPLETED"
      return 0
    fi
    poll_status=$(json_field "$body" status)
    if [ "$status_code" = "410" ] || [ "$poll_status" = "EXPIRED" ]; then
      echo "EXPIRED"
      return 0
    fi
    sleep 1
  done
  echo ""
}

wait_readiness() {
  # wait_readiness <base_url> <up|down> <max_tries>
  # -m 10: a readiness check that must itself detect a fresh Redis TCP disconnect can take several
  # seconds inside the app before the health indicator flips and responds — a short curl timeout
  # reads as "no answer" (curl exit, http_code 000) rather than the real 503, so give it room.
  local base_url="$1" want="$2" max_tries="${3:-20}"
  local code
  for _ in $(seq 1 "$max_tries"); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "${base_url}/health/readiness" || echo "000")
    if [ "$want" = "up" ] && [ "$code" = "200" ]; then return 0; fi
    if [ "$want" = "down" ] && [ "$code" != "200" ]; then return 0; fi
    sleep 1
  done
  return 1
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
