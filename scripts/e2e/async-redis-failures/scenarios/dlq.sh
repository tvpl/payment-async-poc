#!/usr/bin/env bash
# RED-07: a poison message always reaches the DLQ with its reason, and is ACKed off the main
# stream only after the DLQ write is confirmed — proven for both DLQ triggers: a malformed
# payload (rejected on first delivery, bypassing the HTTP API entirely so validation there can't
# hide the check) and a payload that genuinely exceeds max-deliveries (via the fail-on-reference
# test hook, forcing repeated processing failure until the delivery cap trips).
#
# Each scratch container here uses its own consumer GROUP name (not the shared default
# "workers"), not just its own stream. ReclaimCoordinator's lease key is `reclaim:{group}:owner`
# — scoped by group only — so a scratch deployment sharing the default group name with the
# already-running primary/async-redis-2 fleet would starve behind their continuously-renewed
# lease and its own pending entries would never get scanned. Production never hits this: a real
# fleet deliberately shares one group for one stream. It only bites an isolated test harness that
# spins up logically separate deployments side by side.

scenario_malformed_message_dlq_before_ack() {
  local name="malformed-message-dlq-before-ack"
  local port=8100
  local base="http://localhost:${port}"
  local stream="async.jobs.t56dlqmalformed"
  local dlq="async.jobs.t56dlqmalformed.dlq"

  docker run -d --name async-redis-t56-dlq --network "$SANDBOX_NETWORK" -p "${port}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56dlq -e ASYNC_REDIS_STREAM="$stream" \
    -e ASYNC_REDIS_GROUP=t56dlqmalformedworkers -e ASYNC_REDIS_DLQ_STREAM="$dlq" \
    async-redis-service:local >/dev/null
  wait_healthy async-redis-t56-dlq || true

  # Bypasses the HTTP API entirely (like T55's poison scenario bypassing payment-api) — a raw
  # XADD with no jobId is something the API's own request validation could never produce, so this
  # proves the worker's own defense, not just the controller's input check.
  redis_cli XADD "$stream" '*' reference badref amountCents 100 >/dev/null

  sleep 2
  local dlq_entry
  dlq_entry=$(redis_cli XRANGE "$dlq" - +)
  require "$name" "a malformed message (no jobId) reaches the DLQ with reason missing-job-id" \
    bash -c "printf '%s\n' \"$dlq_entry\" | grep -q 'missing-job-id'"

  local pending
  pending=$(redis_cli XPENDING "$stream" t56dlqmalformedworkers - + 10)
  require "$name" "the malformed message is ACKed off the main stream (DLQ write happened before ACK, nothing left dangling)" \
    test -z "$pending"

  docker rm -f async-redis-t56-dlq >/dev/null 2>&1 || true
}

scenario_exceeded_deliveries_dlq() {
  local name="exceeded-deliveries-dlq"
  local port=8101
  local base="http://localhost:${port}"
  local stream="async.jobs.t56dlqexceeded"
  local dlq="async.jobs.t56dlqexceeded.dlq"
  local group="t56dlqexceededworkers"

  docker run -d --name async-redis-t56-dlq2 --network "$SANDBOX_NETWORK" -p "${port}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56dlq2 -e ASYNC_REDIS_STREAM="$stream" \
    -e ASYNC_REDIS_GROUP="$group" -e ASYNC_REDIS_DLQ_STREAM="$dlq" \
    -e ASYNC_MAX_DELIVERIES=1 \
    -e ASYNC_REDIS_RECLAIM_IDLE=2s -e ASYNC_REDIS_RECLAIM_INTERVAL=1s -e ASYNC_REDIS_RECLAIM_LEASE=3s \
    -e ASYNC_REDIS_FAIL_ON_REFERENCE=poison-me \
    async-redis-service:local >/dev/null
  wait_healthy async-redis-t56-dlq2 || true

  submit_job "$base" "t56-red07-poison-$RANDOM" "poison-me" >/dev/null

  sleep 6
  local dlq_entry
  dlq_entry=$(redis_cli XRANGE "$dlq" - +)
  require "$name" "a message whose processing keeps failing is DLQ'd once deliveries reach max-deliveries=1" \
    bash -c "printf '%s\n' \"$dlq_entry\" | grep -q 'max-deliveries-exceeded'"

  local pending
  pending=$(redis_cli XPENDING "$stream" "$group" - + 10)
  require "$name" "the exceeded-deliveries message is ACKed off the PEL after the DLQ write, not left retrying forever" \
    test -z "$pending"

  docker rm -f async-redis-t56-dlq2 >/dev/null 2>&1 || true
}
