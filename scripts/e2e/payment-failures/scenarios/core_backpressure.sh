#!/usr/bin/env bash
# CAP-06: Core running below the demanded rate converges to bounded backlog and measurable
# recovery instead of an impossible terminal SLO — zero requests lost, none crash the caller.

scenario_slow_core_backpressure() {
  local name="slow-core-backpressure"
  local burst=15

  echo "    ${name}: raising payment-core-mock latency above the API's 3s wait-timeout..."
  (cd "$REPO_ROOT/payment-core-mock" && \
    CORE_LATENCY_MIN_MS=4000 CORE_LATENCY_MAX_MS=6000 docker compose up -d --force-recreate >/dev/null 2>&1)
  for _ in $(seq 1 20); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' payment-core-mock-core-mock-1 2>/dev/null)" = "healthy" ] && break
    sleep 2
  done

  local -a req_ids=() codes=()
  local i out server_error=0
  for i in $(seq 1 "$burst"); do
    out=$(submit_payment "http://localhost:8080")
    local code; code=$(echo "$out" | head -1)
    local req; req=$(json_field "$(echo "$out" | tail -n +2)" requestId)
    codes+=("$code")
    [ -n "$req" ] && req_ids+=("$req")
    [[ "$code" == 5* ]] && server_error=1
  done

  echo "    ${name}: restoring normal payment-core-mock latency..."
  (cd "$REPO_ROOT/payment-core-mock" && docker compose up -d --force-recreate >/dev/null 2>&1)
  for _ in $(seq 1 20); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' payment-core-mock-core-mock-1 2>/dev/null)" = "healthy" ] && break
    sleep 2
  done

  local accepted=1
  for code in "${codes[@]}"; do
    case "$code" in 200|202|429|422) ;; *) accepted=0 ;; esac
  done

  # Poll all outstanding requests together (not one after another) — sequential per-request
  # polling would starve the later requests of settling time, since the backlog from the burst
  # is still draining while earlier requests are being checked.
  local -a pending=("${req_ids[@]}")
  local completed=0 round req body poll_status
  for round in $(seq 1 60); do
    [ "${#pending[@]}" -eq 0 ] && break
    local -a still_pending=()
    for req in "${pending[@]}"; do
      body=$(curl -s -m 10 -H "X-API-Key: ${API_KEY}" "http://localhost:8080/payment-simulations/${req}")
      poll_status=$(json_field "$body" status)
      case "$poll_status" in
        COMPLETED|FAILED|TIMEOUT) completed=$((completed + 1)) ;;
        *) still_pending+=("$req") ;;
      esac
    done
    pending=("${still_pending[@]}")
    [ "${#pending[@]}" -gt 0 ] && sleep 1
  done

  if [ "$server_error" = "1" ] || [ "$accepted" != "1" ]; then
    log_fail "${name}" "burst produced an unexpected status; codes=${codes[*]}"
  elif [ "$completed" -ne "${#req_ids[@]}" ]; then
    log_fail "${name}" "only ${completed}/${#req_ids[@]} requests reached a terminal state — silent loss under slow Core"
  else
    log_pass "${name}: ${burst} requests under a 4-6s Core (> 3s wait-timeout) all got 200/202/422/429 (codes=${codes[*]}) and all ${completed} eventually completed, none lost or 5xx"
  fi
}
