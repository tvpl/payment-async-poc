#!/usr/bin/env bash
# RED-08: POST /jobs has idempotency, auth and admission enabled in the production profile — or
# the service refuses to start. ProductionAcceptanceGuard only activates under
# @Requires(env="prod"), so this must boot real containers with MICRONAUT_ENVIRONMENTS=prod, not
# just flip flags under dev (which the existing JobAcceptanceGatesIT already covers via runtime
# 401/400s, without ever loading this guard bean). Checks each gate individually refusing startup,
# plus a positive control proving a correctly configured prod container does start.

scenario_production_guard_rejects_insecure_config() {
  local name="production-guard-rejects-insecure-config"
  local real_key="a-real-production-key-not-the-dev-default"

  docker run -d --name async-redis-t56-prod-devkey --network "$SANDBOX_NETWORK" \
    -e MICRONAUT_ENVIRONMENTS=prod -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56prodbad -e ASYNC_REDIS_STREAM=async.jobs.t56prodbad \
    -e ASYNC_REDIS_SECURITY_ENABLED=true -e ASYNC_REDIS_SECURITY_API_KEYS=dev-key-change-me \
    -e ASYNC_REDIS_IDEMPOTENCY_REQUIRED=true -e ASYNC_ADMISSION_LIMIT=100 \
    async-redis-service:local >/dev/null
  docker run -d --name async-redis-t56-prod-noidem --network "$SANDBOX_NETWORK" \
    -e MICRONAUT_ENVIRONMENTS=prod -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56noidem -e ASYNC_REDIS_STREAM=async.jobs.t56noidem \
    -e ASYNC_REDIS_SECURITY_ENABLED=true -e ASYNC_REDIS_SECURITY_API_KEYS="$real_key" \
    -e ASYNC_REDIS_IDEMPOTENCY_REQUIRED=false -e ASYNC_ADMISSION_LIMIT=100 \
    async-redis-service:local >/dev/null
  docker run -d --name async-redis-t56-prod-noadmit --network "$SANDBOX_NETWORK" \
    -e MICRONAUT_ENVIRONMENTS=prod -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56noadmit -e ASYNC_REDIS_STREAM=async.jobs.t56noadmit \
    -e ASYNC_REDIS_SECURITY_ENABLED=true -e ASYNC_REDIS_SECURITY_API_KEYS="$real_key" \
    -e ASYNC_REDIS_IDEMPOTENCY_REQUIRED=true -e ASYNC_ADMISSION_LIMIT=0 \
    async-redis-service:local >/dev/null
  docker run -d --name async-redis-t56-prod-good --network "$SANDBOX_NETWORK" \
    -e MICRONAUT_ENVIRONMENTS=prod -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=t56prodgood -e ASYNC_REDIS_STREAM=async.jobs.t56prodgood \
    -e ASYNC_REDIS_SECURITY_ENABLED=true -e ASYNC_REDIS_SECURITY_API_KEYS="$real_key" \
    -e ASYNC_REDIS_IDEMPOTENCY_REQUIRED=true -e ASYNC_ADMISSION_LIMIT=100 \
    async-redis-service:local >/dev/null

  sleep 8

  local status
  status=$(docker inspect -f '{{.State.Status}}' async-redis-t56-prod-devkey 2>/dev/null)
  require "$name" "prod startup refuses the dev-default API key (async.redis.security.api-keys)" \
    test "$status" = "exited"

  status=$(docker inspect -f '{{.State.Status}}' async-redis-t56-prod-noidem 2>/dev/null)
  require "$name" "prod startup refuses idempotency-required=false" \
    test "$status" = "exited"

  status=$(docker inspect -f '{{.State.Status}}' async-redis-t56-prod-noadmit 2>/dev/null)
  require "$name" "prod startup refuses admission-limit-per-sec=0 (disabled)" \
    test "$status" = "exited"

  status=$(docker inspect -f '{{.State.Health.Status}}' async-redis-t56-prod-good 2>/dev/null)
  require "$name" "a correctly configured prod container (real key + idempotency + admission all on) starts healthy" \
    test "$status" = "healthy"

  docker rm -f async-redis-t56-prod-devkey async-redis-t56-prod-noidem \
    async-redis-t56-prod-noadmit async-redis-t56-prod-good >/dev/null 2>&1 || true
}
