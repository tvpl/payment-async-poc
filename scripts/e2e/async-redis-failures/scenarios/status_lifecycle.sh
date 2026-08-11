#!/usr/bin/env bash
# RED-01: status is persisted and queryable through every state — missing/unknown, processing,
# terminal and expired — never a silent gap. Two ephemeral scratch containers: one with inflated
# processing latency (to observe PROCESSING before terminal), one with a compressed TTL pair (to
# observe EXPIRED without waiting out the 15m production default).
#
# NB: local vars are named "code"/"job_state", never "status" — zsh treats $status as a reserved
# read-only special parameter (last exit code), and this repo's default shell may be zsh.

scenario_status_lifecycle_all_states() {
  local name="status-lifecycle-all-states"
  local slow_port=8095 short_port=8096
  local slow_base="http://localhost:${slow_port}" short_base="http://localhost:${short_port}"

  docker run -d --name async-redis-t56-slow --network "$SANDBOX_NETWORK" -p "${slow_port}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56slow -e ASYNC_REDIS_STREAM=async.jobs.t56slow \
    -e ASYNC_LATENCY_MIN_MS=5000 -e ASYNC_LATENCY_MAX_MS=6000 \
    async-redis-service:local >/dev/null
  docker run -d --name async-redis-t56-shortttl --network "$SANDBOX_NETWORK" -p "${short_port}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56shortttl -e ASYNC_REDIS_STREAM=async.jobs.t56shortttl \
    -e ASYNC_REDIS_RESULT_TTL=3s -e ASYNC_REDIS_STATUS_TTL=8s \
    async-redis-service:local >/dev/null

  wait_healthy async-redis-t56-slow || true
  wait_healthy async-redis-t56-shortttl || true

  # --- unknown: never-submitted jobId ---
  local resp code body
  resp=$(get_job "$short_base" "00000000-0000-0000-0000-000000000000")
  code=$(printf '%s' "$resp" | head -1)
  body=$(printf '%s' "$resp" | tail -n +2)
  require "$name" "GET on a never-submitted jobId returns 404 UNKNOWN, not a hang or 500" \
    bash -c "[ '$code' = '404' ] && [ '$(json_field "$body" status)' = 'UNKNOWN' ]"

  # --- processing: latency (5-6s) exceeds wait-timeout (3s default), so both the POST response
  # and an immediate follow-up GET must show PROCESSING before the job reaches a terminal state ---
  resp=$(submit_job "$slow_base" "t56-red01-processing-$RANDOM" "t56-red01-processing")
  code=$(printf '%s' "$resp" | head -1)
  body=$(printf '%s' "$resp" | tail -n +2)
  local job_id
  job_id=$(json_field "$body" jobId)
  require "$name" "POST blocks up to wait-timeout then returns 202 PROCESSING (not blocking past the HTTP budget)" \
    bash -c "[ '$code' = '202' ] && [ '$(json_field "$body" status)' = 'PROCESSING' ]"

  resp=$(get_job "$slow_base" "$job_id")
  code=$(printf '%s' "$resp" | head -1)
  body=$(printf '%s' "$resp" | tail -n +2)
  require "$name" "a status persisted before enqueue is queryable as PROCESSING while still in flight" \
    bash -c "[ '$code' = '202' ] && [ '$(json_field "$body" status)' = 'PROCESSING' ]"

  local job_state
  job_state=$(poll_terminal "$slow_base" "$job_id" 15)
  require "$name" "the same job eventually reaches a terminal COMPLETED state" \
    test "$job_state" = "COMPLETED"

  # --- completed then expired: result-ttl (3s) is shorter than status-ttl (8s), so after the
  # result expires but before the status key does, the job must report EXPIRED, not UNKNOWN ---
  resp=$(submit_job "$short_base" "t56-red01-expiry-$RANDOM" "t56-red01-expiry")
  code=$(printf '%s' "$resp" | head -1)
  body=$(printf '%s' "$resp" | tail -n +2)
  job_id=$(json_field "$body" jobId)
  require "$name" "a fast job completes synchronously (200 COMPLETED) within the wait budget" \
    bash -c "[ '$code' = '200' ] && [ '$(json_field "$body" status)' = 'COMPLETED' ]"

  sleep 5
  resp=$(get_job "$short_base" "$job_id")
  code=$(printf '%s' "$resp" | head -1)
  body=$(printf '%s' "$resp" | tail -n +2)
  require "$name" "after result-ttl elapses (before status-ttl) the job reports 410 EXPIRED, not UNKNOWN" \
    bash -c "[ '$code' = '410' ] && [ '$(json_field "$body" status)' = 'EXPIRED' ]"

  docker rm -f async-redis-t56-slow async-redis-t56-shortttl >/dev/null 2>&1 || true
}
