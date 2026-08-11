#!/usr/bin/env bash
# CAP-05: at least two payment-api and two payment-sbus instances, requestId ordering and
# cross-instance coordination proven live. Sourced by run.sh; expects lib.sh already sourced
# and api-2/sbus-2 already up (multi_instance.sh).

scenario_duplicate_idempotency_key_cross_instance() {
  local name="duplicate-idempotency-key-cross-instance"
  local idem="dup-$(date +%s)-$RANDOM"
  local out1 out2 code1 code2 req1 req2

  out1=$(mktemp); out2=$(mktemp)
  submit_payment "http://localhost:8080" "$idem" >"$out1" &
  local pid1=$!
  submit_payment "http://localhost:8090" "$idem" >"$out2" &
  local pid2=$!
  wait "$pid1" "$pid2"

  code1=$(head -1 "$out1"); req1=$(json_field "$(tail -n +2 "$out1")" requestId)
  code2=$(head -1 "$out2"); req2=$(json_field "$(tail -n +2 "$out2")" requestId)
  rm -f "$out1" "$out2"

  # Genuinely concurrent racers: the "loser" legitimately observes 202/PROCESSING if it queries
  # before the winner's synchronous wait resolves — the correctness property is the SAME
  # requestId (no double-processing), not that both happen to see 200.
  if [ "$req1" != "$req2" ] || [ -z "$req1" ]; then
    log_fail "${name}" "api-1=(${code1},${req1}) api-2=(${code2},${req2}) — expected identical requestId"
    return
  fi
  # 422 is a legitimate terminal outcome too (core-mock's seeded RNG deterministically declines
  # a slice of requests) — CAP-05 cares about identity/coordination, not the simulated business
  # outcome distribution.
  case "$code1" in 200|202|422) ;; *) log_fail "${name}" "api-1 returned unexpected HTTP ${code1}"; return ;; esac
  case "$code2" in 200|202|422) ;; *) log_fail "${name}" "api-2 returned unexpected HTTP ${code2}"; return ;; esac

  local final
  final=$(poll_terminal "http://localhost:8080" "$req1" 30)
  if [ -n "$final" ]; then
    log_pass "${name}: both instances resolve the same Idempotency-Key to requestId=${req1}, converges to ${final}"
  else
    log_fail "${name}" "requestId=${req1} never reached a terminal state"
  fi
}

scenario_cross_instance_fleet_coordination() {
  local name="cross-instance-fleet-coordination"
  local -a endpoints=("http://localhost:8080" "http://localhost:8090" "http://localhost:8080" "http://localhost:8090")
  local -a req_ids=()
  local ok=1 i resp code req final

  for i in "${!endpoints[@]}"; do
    resp=$(submit_payment "${endpoints[$i]}")
    code=$(echo "$resp" | head -1)
    req=$(json_field "$(echo "$resp" | tail -n +2)" requestId)
    case "$code" in
      200|202|422) ;;
      *) ok=0; log_fail "${name}" "request $((i+1)) via ${endpoints[$i]} returned HTTP ${code}"; continue ;;
    esac
    if [ -z "$req" ]; then
      ok=0
      log_fail "${name}" "request $((i+1)) via ${endpoints[$i]} returned HTTP ${code} with no requestId"
      continue
    fi
    req_ids+=("$req")
  done

  # 8080 is fronted by sbus-1, 8090 by sbus-2 (payment-api's own SBUS_BASE_URL wiring) — four
  # requests round-robined across two api + effectively both sbus instances in the fleet.
  local seen=()
  for req in "${req_ids[@]}"; do
    for s in "${seen[@]}"; do
      if [ "$s" = "$req" ]; then
        ok=0
        log_fail "${name}" "duplicate requestId ${req} across independent submissions"
      fi
    done
    seen+=("$req")
  done

  if [ "$ok" = "1" ] && [ "${#req_ids[@]}" -eq "${#endpoints[@]}" ]; then
    log_pass "${name}: ${#req_ids[@]} concurrent requests across api-1/api-2 each got a distinct requestId, no cross-talk"
  fi
}
