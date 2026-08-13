#!/usr/bin/env bash
# PAY-05/06/08: outbox lease/ownership recovery, terminal-result stability under a forced
# duplicate republish, and due-based retry not blocking live traffic. Sourced by run.sh.

scenario_outbox_crash_window_reclaim() {
  local name="outbox-crash-window-reclaim"
  local resp req before_body before_auth row_id

  resp=$(submit_payment "http://localhost:8080")
  req=$(json_field "$(echo "$resp" | tail -n +2)" requestId)
  if [ -z "$req" ]; then log_fail "${name}" "setup: submit_payment returned no requestId"; return; fi
  sleep 2

  # payment-core-mock's approve/decline decision is probabilistic (PAY-06 must hold either way),
  # so the terminal topic is 'payment.simulation.completed' OR 'payment.simulation.failed' — never
  # pin to one, or this setup fails at random on a genuine decline instead of a real defect.
  row_id=$(psql_sandbox "SELECT id FROM outbox_event WHERE aggregate_id = '${req}' AND topic IN ('payment.simulation.completed','payment.simulation.failed') AND status = 'PUBLISHED' ORDER BY id DESC LIMIT 1;")
  row_id=$(echo "$row_id" | tr -d '[:space:]')
  if [ -z "$row_id" ]; then
    local sim_status
    sim_status=$(psql_sandbox "SELECT status FROM payment_sbus_message WHERE request_id = '${req}';" | tr -d '[:space:]')
    log_fail "${name}" "setup: no PUBLISHED terminal-event outbox row found for ${req} (simulation status: ${sim_status:-unknown})"
    return
  fi

  before_body=$(curl -s -m 10 -H "X-API-Key: ${API_KEY}" "http://localhost:8080/payment-simulations/${req}")
  before_auth=$(json_field "$before_body" "result.authorizationCode")

  # Simulate "crashed right after Kafka ack, before the outbox mark": roll this already-published
  # row's fencing fields back to a stale IN_PROGRESS claim, as if a process died mid-publish.
  psql_sandbox "UPDATE outbox_event SET status='IN_PROGRESS', claimed_at = now() - interval '5 minutes', claim_token = gen_random_uuid() WHERE id = ${row_id};" >/dev/null

  echo "    ${name}: forced row ${row_id} back to a stale IN_PROGRESS claim, waiting for OutboxReaper (lease 1m + reaper-interval 30s)..."
  local final_status
  final_status=""
  for _ in $(seq 1 40); do
    sleep 3
    final_status=$(psql_sandbox "SELECT status FROM outbox_event WHERE id = ${row_id};" | tr -d '[:space:]')
    [ "$final_status" = "PUBLISHED" ] && break
  done

  local after_body after_auth
  after_body=$(curl -s -m 10 -H "X-API-Key: ${API_KEY}" "http://localhost:8080/payment-simulations/${req}")
  after_auth=$(json_field "$after_body" "result.authorizationCode")

  if [ "$final_status" != "PUBLISHED" ]; then
    log_fail "${name}" "row ${row_id} never returned to PUBLISHED after forced crash (last seen: ${final_status})"
  elif [ "$after_auth" != "$before_auth" ] || [ -z "$after_auth" ]; then
    log_fail "${name}" "terminal result changed after forced duplicate republish: before=${before_auth} after=${after_auth}"
  else
    log_pass "${name}: reclaimed+republished row ${row_id} (PAY-05) without altering the already-chosen terminal result (PAY-06, authorizationCode=${after_auth})"
  fi
}

scenario_sbus_kill_mid_flight() {
  local name="sbus-container-kill-mid-flight"
  local resp req final

  resp=$(submit_payment "http://localhost:8080")
  req=$(json_field "$(echo "$resp" | tail -n +2)" requestId)
  if [ -z "$req" ]; then log_fail "${name}" "setup: submit_payment returned no requestId"; return; fi

  docker kill payment-sbus-sbus-1 >/dev/null 2>&1

  # `docker kill` marks the container manually-stopped, so `restart: unless-stopped` does not
  # bring it back on its own (that policy only covers a genuine process crash/OOM). What PAY-05
  # actually promises is that killing one instance doesn't lose or duplicate the request —
  # sbus-2, still up, must pick up the slack via the same FOR UPDATE SKIP LOCKED claim query.
  final=$(poll_terminal "http://localhost:8080" "$req" 45)

  echo "    ${name}: restarting payment-sbus-sbus-1 (operator/orchestrator action) to restore the fleet for later scenarios..."
  docker start payment-sbus-sbus-1 >/dev/null 2>&1
  local recovered=0
  for _ in $(seq 1 30); do
    sleep 2
    if [ "$(docker inspect -f '{{.State.Health.Status}}' payment-sbus-sbus-1 2>/dev/null)" = "healthy" ]; then
      recovered=1; break
    fi
  done

  if [ -z "$final" ]; then
    log_fail "${name}" "requestId=${req} never reached a terminal state after killing sbus-1 mid-flight"
  elif [ "$recovered" != "1" ]; then
    log_fail "${name}" "payment-sbus-sbus-1 did not come back healthy after a manual restart"
  else
    # Docker's healthcheck only proves the HTTP listener answers, not that the freshly-restarted
    # JVM's @Scheduled outbox poller has run its first cycle (outbox.initial-delay) — a small
    # settle margin avoids the next scenario racing that warmup.
    sleep 5
    log_pass "${name}: requestId=${req} still reached ${final} after a hard kill of sbus-1 — sbus-2 covered the fleet, restarted container is healthy again"
  fi
}

scenario_due_retry_does_not_block_live_traffic() {
  local name="due-retry-does-not-block-live-traffic"
  local future_key="future-retry-$(date +%s)-$RANDOM"

  psql_sandbox "INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, topic, message_key, headers, status, next_attempt_at, created_at, payload) VALUES ('PaymentSimulation', '${future_key}', 'PaymentSimulationCompleted', 'payment.simulation.completed', '${future_key}', '{}'::jsonb, 'PENDING', now() + interval '2 minutes', now(), '\\x00');" >/dev/null

  local start_ts resp req final elapsed
  start_ts=$(date +%s)
  resp=$(submit_payment "http://localhost:8080")
  req=$(json_field "$(echo "$resp" | tail -n +2)" requestId)
  final=$(poll_terminal "http://localhost:8080" "$req" 40)
  elapsed=$(( $(date +%s) - start_ts ))

  local future_state
  future_state=$(psql_sandbox "SELECT status FROM outbox_event WHERE aggregate_id = '${future_key}';" | tr -d '[:space:]')

  if [ -z "$final" ]; then
    log_fail "${name}" "live request never reached terminal state while a future retry (not-before +2m) was pending"
  elif [ "$elapsed" -gt 30 ]; then
    log_fail "${name}" "live request took ${elapsed}s (expected a few seconds) — future retry appears to be blocking the claim queue"
  elif [ "$future_state" != "PENDING" ]; then
    log_fail "${name}" "future-dated row was claimed early (status=${future_state}) before its next_attempt_at"
  else
    log_pass "${name}: live request completed in ${elapsed}s (${final}) while the future-dated retry correctly stayed PENDING/unclaimed"
  fi

  psql_sandbox "DELETE FROM outbox_event WHERE aggregate_id = '${future_key}';" >/dev/null
}
