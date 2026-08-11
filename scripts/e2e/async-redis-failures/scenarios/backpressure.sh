#!/usr/bin/env bash
# RED-02: when the wait-pool is exhausted, acquisition is bounded (pool-max-wait, not the full
# HTTP wait-timeout) and the caller gets explicit backpressure — never an unbounded block, and
# never a lost job.

scenario_wait_pool_backpressure() {
  local name="wait-pool-backpressure"
  local port=8097
  local base="http://localhost:${port}"

  docker run -d --name async-redis-t56-backpressure --network "$SANDBOX_NETWORK" -p "${port}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56bp -e ASYNC_REDIS_STREAM=async.jobs.t56bp \
    -e ASYNC_LATENCY_MIN_MS=4000 -e ASYNC_LATENCY_MAX_MS=4500 \
    -e ASYNC_POOL_MAX_TOTAL=1 -e ASYNC_REDIS_POOL_MAX_WAIT=300ms \
    async-redis-service:local >/dev/null
  wait_healthy async-redis-t56-backpressure || true

  local r1_body r1_time r2_body r2_headers r2_time
  r1_body=$(mktemp); r2_body=$(mktemp); r2_headers=$(mktemp)

  # r1 holds the pool's single connection for the full processing latency (>wait-timeout, so it
  # times out its own wait and returns 202 PROCESSING around the 3s wait-timeout mark).
  (
    curl -s -o "$r1_body" -w '%{http_code}' -m 30 \
      -X POST "${base}/jobs" -H 'Content-Type: application/json' \
      -H "X-API-Key: ${API_KEY}" -H "Idempotency-Key: bp-r1-$RANDOM" \
      -d '{"reference":"bp-r1","amountCents":100,"note":"holds the pool"}' > "${r1_body}.code"
  ) &
  sleep 0.3
  # r2 arrives while the pool is exhausted; it must not wait out the 4-4.5s latency — pool-max-wait
  # (300ms) bounds acquisition, so it should return fast with the backpressure header.
  local t0 t1 r2_elapsed
  t0=$(date +%s.%N)
  curl -s -D "$r2_headers" -o "$r2_body" -w '%{http_code}' -m 10 \
    -X POST "${base}/jobs" -H 'Content-Type: application/json' \
    -H "X-API-Key: ${API_KEY}" -H "Idempotency-Key: bp-r2-$RANDOM" \
    -d '{"reference":"bp-r2","amountCents":200,"note":"finds no capacity"}' > "${r2_body}.code"
  t1=$(date +%s.%N)
  r2_elapsed=$(awk "BEGIN{print $t1-$t0}")
  wait

  require "$name" "backpressured request returns well within pool-max-wait, not the full processing latency" \
    bash -c "awk -v e=\"$r2_elapsed\" 'BEGIN{exit !(e < 2)}'"

  require "$name" "backpressured response carries X-Backpressure: wait-pool-exhausted" \
    grep -qi "X-Backpressure: wait-pool-exhausted" "$r2_headers"

  local r2_job_id r2_state
  r2_job_id=$(json_field "$(cat "$r2_body")" jobId)
  r2_state=$(poll_terminal "$base" "$r2_job_id" 15)
  require "$name" "a job that hit backpressure was still enqueued and completes asynchronously" \
    test "$r2_state" = "COMPLETED"

  rm -f "$r1_body" "$r1_body.code" "$r2_body" "$r2_body.code" "$r2_headers"
  docker rm -f async-redis-t56-backpressure >/dev/null 2>&1 || true
}
