#!/usr/bin/env bash
# PAY-09: each affected service applies timeout/retry/circuit policy and readiness compatible
# with its documented guarantee when Kafka, PostgreSQL, Redis or the Schema Registry is down.
# Each scenario stops exactly one sandbox dependency, submits through the real API, restores
# the dependency, and checks recovery — matching design.md §7.2's failure matrix row by row.

scenario_kafka_unavailable() {
  local name="kafka-unavailable-on-publish"
  local idem="kafka-down-$(date +%s)-$RANDOM"

  docker stop payment-sandbox-kafka-1 >/dev/null 2>&1
  sleep 2

  echo "    ${name}: submitting — the producer's delivery.timeout.ms default means this can take ~2 minutes to fail..."
  local out code body
  out=$(submit_payment "http://localhost:8080" "$idem")
  code=$(echo "$out" | head -1); body=$(echo "$out" | tail -n +2)

  docker start payment-sandbox-kafka-1 >/dev/null 2>&1
  wait_healthy payment-sandbox-kafka-1 40

  if [ "$code" != "503" ]; then
    log_fail "${name}" "expected 503 while Kafka is down, got ${code}: ${body}"
    return
  fi
  if [[ "$body" != *'"status":503'* ]]; then
    log_fail "${name}" "503 body missing the documented Problem+JSON shape: ${body}"
    return
  fi

  local retry_out retry_code retry_req
  retry_out=$(submit_payment "http://localhost:8080" "$idem")
  retry_code=$(echo "$retry_out" | head -1)
  retry_req=$(json_field "$(echo "$retry_out" | tail -n +2)" requestId)
  local final; final=$(poll_terminal "http://localhost:8080" "$retry_req" 20)

  case "$retry_code" in
    200|202|422) ;;
    *) log_fail "${name}" "idempotent retry after Kafka recovered returned ${retry_code}"; return ;;
  esac
  if [ -z "$final" ]; then
    log_fail "${name}" "idempotent retry after Kafka recovered never reached a terminal state"
  else
    log_pass "${name}: 503 with a recoverable reservation while Kafka was down; the same Idempotency-Key completed (${final}) once it recovered"
  fi
}

scenario_redis_unavailable_api() {
  local name="redis-unavailable-api"

  docker stop payment-sandbox-redis-1 >/dev/null 2>&1
  sleep 2

  local out code body
  out=$(submit_payment "http://localhost:8080")
  code=$(echo "$out" | head -1); body=$(echo "$out" | tail -n +2)

  docker start payment-sandbox-redis-1 >/dev/null 2>&1
  wait_healthy payment-sandbox-redis-1 20
  # payment-api's own Lettuce client needs a moment to reconnect after Redis comes back — without
  # this, the NEXT scenario's request can race the reconnect and hit the same gap for an unrelated
  # reason (redis-outage bleeding into the following scenario, not that scenario's own dependency).
  sleep 8

  # PAY-09 / design.md §7.2 ("Redis da API indisponível") documents fail-closed: a controlled
  # rejection (503/429), never an unhandled 500 leaking connection internals. Asserting the
  # documented guarantee (not today's behavior) is deliberate — see the spawned follow-up task
  # for RedisStatusStore.reserve(), which currently has no try/catch around the Lettuce call.
  if [ "$code" = "500" ]; then
    log_fail "${name}" "known gap (flagged as a follow-up task): got 500 leaking internals instead of a fail-closed 503/429 — body: ${body}"
  elif [ "$code" = "503" ] || [ "$code" = "429" ]; then
    log_pass "${name}: Redis outage failed closed with HTTP ${code}, no unhandled exception"
  else
    log_fail "${name}" "unexpected HTTP ${code} while Redis was down: ${body}"
  fi
}

scenario_postgres_unavailable_sbus() {
  local name="postgres-unavailable-sbus"

  docker stop payment-sandbox-postgres-1 >/dev/null 2>&1
  sleep 2

  local out code req
  out=$(submit_payment "http://localhost:8080")
  code=$(echo "$out" | head -1)
  req=$(json_field "$(echo "$out" | tail -n +2)" requestId)

  docker start payment-sandbox-postgres-1 >/dev/null 2>&1
  wait_healthy payment-sandbox-postgres-1 20

  if [ -z "$req" ]; then
    log_fail "${name}" "API did not accept the request while SBUS's Postgres was down (HTTP ${code})"
    return
  fi
  local final; final=$(poll_terminal "http://localhost:8080" "$req" 30)
  if [ -z "$final" ]; then
    log_fail "${name}" "requestId=${req} never reached a terminal state after Postgres recovered"
  else
    log_pass "${name}: API accepted the request (Kafka still up) while SBUS's DB was down, and it settled to ${final} once Postgres recovered — no false ack, no loss"
  fi
}

scenario_registry_unavailable() {
  local name="registry-unavailable"

  docker stop payment-sandbox-registry-1 >/dev/null 2>&1
  sleep 2

  local out code body
  out=$(submit_payment "http://localhost:8080")
  code=$(echo "$out" | head -1); body=$(echo "$out" | tail -n +2)

  docker start payment-sandbox-registry-1 >/dev/null 2>&1
  wait_healthy payment-sandbox-registry-1 20
  # apicurio-registry-mem is in-memory only: a stop+start wipes every previously registered
  # schema, so the first publish(es) after recovery re-trigger auto-registration — give that a
  # few seconds and a couple of attempts rather than treating one early retry as the verdict.
  sleep 5

  if [ "$code" != "503" ]; then
    log_fail "${name}" "expected 503 while the Schema Registry is down, got ${code}: ${body}"
    return
  fi

  local retry_out retry_req final attempt
  final=""
  for attempt in 1 2 3; do
    retry_out=$(submit_payment "http://localhost:8080")
    retry_req=$(json_field "$(echo "$retry_out" | tail -n +2)" requestId)
    [ -n "$retry_req" ] && final=$(poll_terminal "http://localhost:8080" "$retry_req" 15)
    [ -n "$final" ] && break
    sleep 3
  done

  if [ -z "$final" ]; then
    log_fail "${name}" "fresh requests after Registry recovered never reached a terminal state (schema re-registration didn't settle)"
  else
    log_pass "${name}: 503 (Avro encode failed closed) while the Registry was down; a fresh request completed (${final}) once it recovered and re-registered its schema"
  fi
}
