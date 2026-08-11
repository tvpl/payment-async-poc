#!/usr/bin/env bash
# RED-06: persistence, wakeup and TTL happen as one atomic/idempotent unit before ACK. Proven by
# stealing PEL ownership of a real in-flight message via XCLAIM to another consumer mid-processing
# (simulating a genuine ownership race) and confirming the original worker still releases exactly
# once (single wakeup entry, single sent marker) and still cleanly ACKs the message off the PEL —
# XACK doesn't require matching the current PEL owner, only real result-releaser idempotency does.

scenario_atomic_release_survives_ownership_theft() {
  local name="atomic-release-survives-ownership-theft"
  local port=8099
  local base="http://localhost:${port}"
  local stream="async.jobs.t56rel"

  docker run -d --name async-redis-t56-release --network "$SANDBOX_NETWORK" -p "${port}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56rel -e ASYNC_REDIS_STREAM="$stream" \
    -e ASYNC_LATENCY_MIN_MS=4000 -e ASYNC_LATENCY_MAX_MS=4500 \
    async-redis-service:local >/dev/null
  wait_healthy async-redis-t56-release || true

  local resp_file
  resp_file=$(mktemp)
  ( submit_job "$base" "t56-red06-key-$RANDOM" "t56-red06-ref" > "$resp_file" ) &

  local entry_id="" pending i
  for i in $(seq 1 20); do
    pending=$(redis_cli XPENDING "$stream" workers - + 10)
    entry_id=$(printf '%s\n' "$pending" | sed -n 1p)
    [ -n "$entry_id" ] && break
    sleep 0.2
  done
  require "$name" "the in-flight message shows up in the PEL while still processing" \
    test -n "$entry_id"

  local claimed
  claimed=$(redis_cli XCLAIM "$stream" workers rogue-consumer 0 "$entry_id" JUSTID)
  require "$name" "a rogue consumer can steal PEL ownership of the still-processing entry (XCLAIM ownership is not ACK authority)" \
    test "$claimed" = "$entry_id"

  wait
  local job_id job_state
  job_id=$(json_field "$(sed -n 2p "$resp_file")" jobId)
  job_state=$(poll_terminal "$base" "$job_id" 15)
  require "$name" "the job still reaches COMPLETED despite the ownership theft" \
    test "$job_state" = "COMPLETED"

  local remaining_pending
  remaining_pending=$(redis_cli XPENDING "$stream" workers - + 10)
  require "$name" "the message is cleanly ACKed off the PEL regardless of who currently owns it" \
    test -z "$remaining_pending"

  local sent_exists wakeup_len
  sent_exists=$(redis_cli EXISTS "resp:${job_id}:sent")
  wakeup_len=$(redis_cli LLEN "resp:${job_id}")
  require "$name" "the wakeup fired exactly once (sent marker set, single list entry — no duplicate push)" \
    bash -c "[ '$sent_exists' = '1' ] && [ '$wakeup_len' = '1' ]"

  rm -f "$resp_file"
  docker rm -f async-redis-t56-release >/dev/null 2>&1 || true
}
