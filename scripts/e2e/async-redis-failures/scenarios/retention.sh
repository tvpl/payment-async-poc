#!/usr/bin/env bash
# RED-03: retention never drops a pending/unconsumed payload, and alerts before the unsafe
# backlog limit — proven by pushing real backlog past stream-maxlen and confirming both the
# stream length (never trimmed) and the scheduled monitor's WARN alert.

scenario_retention_alert_without_autotrim() {
  local name="retention-alert-without-autotrim"
  local port=8098
  local base="http://localhost:${port}"
  local stream="async.jobs.t56ret"

  docker run -d --name async-redis-t56-retention --network "$SANDBOX_NETWORK" -p "${port}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56ret -e ASYNC_REDIS_STREAM="$stream" \
    -e ASYNC_STREAM_MAXLEN=5 -e ASYNC_RETENTION_ALERT_THRESHOLD=0.6 \
    -e ASYNC_REDIS_RETENTION_CHECK_INTERVAL=3s \
    async-redis-service:local >/dev/null
  wait_healthy async-redis-t56-retention || true

  local i
  for i in $(seq 1 10); do
    submit_job "$base" "t56-ret-key-$i-$RANDOM" "t56-ret-ref-$i" >/dev/null &
  done
  wait

  local xlen
  xlen=$(redis_cli XLEN "$stream")
  require "$name" "stream length is never trimmed below entries added, even past stream-maxlen=5" \
    test "$xlen" -ge 10

  local alert_seen=""
  for i in $(seq 1 15); do
    if docker logs async-redis-t56-retention 2>&1 | grep -q 'backlog is .* at or above the safe budget'; then
      alert_seen="yes"
      break
    fi
    sleep 1
  done
  require "$name" "scheduled retention monitor logs a backlog alert once past the safe budget" \
    test "$alert_seen" = "yes"

  docker rm -f async-redis-t56-retention >/dev/null 2>&1 || true
}
