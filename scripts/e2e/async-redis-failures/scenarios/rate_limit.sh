#!/usr/bin/env bash
# RED-02 (bonus): admission-limit-per-sec is a shared Redis-atomic window
# (AsyncRateLimiter.java's own doc comment states this explicitly), not a per-instance in-memory
# counter — proven by splitting a burst across two real instances sharing the same limit and
# confirming the combined accept count matches the single configured limit, not limit x 2.
#
# The limiter's window is a hardcoded 1000ms bucket keyed by unix second (AsyncRateLimiter.java).
# Under a loaded host (this scenario runs last in a ~10-15min full suite), unaligned parallel
# curls can straddle a window boundary and land in two different buckets, which would let more
# than the limit through without that being a real gap — an artifact of test timing, not the
# feature. Aligning the burst to just after a fresh second boundary, with one retry if a run still
# straddles, makes the proof reliable without weakening what it asserts.

_t56_rl_wait_for_second_boundary() {
  # Blocks until early in a new second (sub-100ms), so a parallel burst fired right after has
  # ~900ms of margin to land in the same rate-limit window.
  local frac
  for _ in $(seq 1 20); do
    frac=$((10#$(date +%N)))
    [ "$frac" -lt 100000000 ] && return 0
    sleep 0.02
  done
}

_t56_rl_fire_burst() {
  local base_a="$1" base_b="$2"
  local i
  for i in $(seq 1 5); do
    ( submit_job "$base_a" "t56-rl-a-$i-$RANDOM-$$" "t56-rl-a-$i" > "/tmp/t56_rl_a_$i.out" ) &
  done
  for i in $(seq 1 5); do
    ( submit_job "$base_b" "t56-rl-b-$i-$RANDOM-$$" "t56-rl-b-$i" > "/tmp/t56_rl_b_$i.out" ) &
  done
  wait
}

scenario_admission_limit_shared_across_instances() {
  local name="admission-limit-shared-across-instances"
  local port_a=8102 port_b=8103
  local base_a="http://localhost:${port_a}" base_b="http://localhost:${port_b}"
  local stream="async.jobs.t56rl"

  docker run -d --name async-redis-t56-rl-a --network "$SANDBOX_NETWORK" -p "${port_a}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56rla -e ASYNC_REDIS_STREAM="$stream" \
    -e ASYNC_REDIS_GROUP=t56rlworkers -e ASYNC_ADMISSION_LIMIT=5 \
    async-redis-service:local >/dev/null
  docker run -d --name async-redis-t56-rl-b --network "$SANDBOX_NETWORK" -p "${port_b}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56rlb -e ASYNC_REDIS_STREAM="$stream" \
    -e ASYNC_REDIS_GROUP=t56rlworkers -e ASYNC_ADMISSION_LIMIT=5 \
    async-redis-service:local >/dev/null
  wait_healthy async-redis-t56-rl-a || true
  wait_healthy async-redis-t56-rl-b || true

  local accepted=0 rejected=0 code attempt
  for attempt in 1 2 3; do
    _t56_rl_wait_for_second_boundary
    _t56_rl_fire_burst "$base_a" "$base_b"

    accepted=0; rejected=0
    for i in $(seq 1 5); do
      code=$(head -1 "/tmp/t56_rl_a_$i.out")
      [ "$code" = "429" ] && rejected=$((rejected + 1)) || accepted=$((accepted + 1))
      code=$(head -1 "/tmp/t56_rl_b_$i.out")
      [ "$code" = "429" ] && rejected=$((rejected + 1)) || accepted=$((accepted + 1))
    done

    # A clean single-window sample looks like accepted<=6 AND rejected>=4. If a straddle still
    # happened (rare after alignment), both instances' full quota can go through — retry rather
    # than fail on a timing artifact.
    if [ "$accepted" -le 6 ] && [ "$rejected" -ge 4 ]; then
      break
    fi
    echo "    (attempt ${attempt}: accepted=${accepted} rejected=${rejected}, retrying — likely window straddle)"
  done

  require "$name" "combined accepts across both instances stay near the single shared limit (5), not limit x 2 (10)" \
    test "$accepted" -le 6

  require "$name" "the burst that exceeded the shared limit was rejected with 429, not silently over-admitted" \
    test "$rejected" -ge 4

  rm -f /tmp/t56_rl_a_*.out /tmp/t56_rl_b_*.out
  docker rm -f async-redis-t56-rl-a async-redis-t56-rl-b >/dev/null 2>&1 || true
}
