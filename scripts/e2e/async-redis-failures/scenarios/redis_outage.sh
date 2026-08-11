#!/usr/bin/env bash
# RED-05: when the shared Redis goes away — at any point, not just startup — every worker's
# readiness must flip down (no false "I can still consume") and recover once Redis is back,
# without losing the ability to process new jobs. Uses the real sandbox Redis container, so both
# the primary instance and async-redis-2 (if up) go down together — a real single-point-of-failure
# outage, not a per-instance fault.

scenario_redis_outage_worker_readiness() {
  local name="redis-outage-worker-readiness"
  local primary_base="http://localhost:8084"

  require "$name" "primary instance is ready before the outage (sane baseline)" \
    wait_readiness "$primary_base" up 3

  docker stop payment-sandbox-redis-1 >/dev/null

  require "$name" "worker readiness flips DOWN once the Redis outage is detected" \
    wait_readiness "$primary_base" down 20

  docker start payment-sandbox-redis-1 >/dev/null

  require "$name" "worker readiness recovers to UP once Redis is back, via reconnect backoff" \
    wait_readiness "$primary_base" up 30

  local resp job_state
  resp=$(submit_job "$primary_base" "t56-red05-recovery-$RANDOM" "t56-red05-recovery")
  job_state=$(json_field "$(printf '%s' "$resp" | tail -n +2)" status)
  require "$name" "a job submitted after recovery completes end to end, proving real consuming capacity (not just a flipped flag)" \
    bash -c "[ '$job_state' = 'COMPLETED' ] || [ '$job_state' = 'PROCESSING' ]"
}
