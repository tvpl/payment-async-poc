#!/usr/bin/env bash
# Brings up a second async-redis-service container (async-redis-2) alongside the primary T54
# already started, so RED-04 can be proven against a real fleet sharing one Redis instead of a
# single process. Uses `docker run` directly (not `docker compose up --scale`) because
# compose.yaml binds a fixed host port, which --scale cannot share — same pattern as
# payment-failures/multi_instance.sh.
set -euo pipefail

SANDBOX_NETWORK="${SANDBOX_NETWORK:-payment-sandbox}"
ASYNC_HOST_PORT_2="${ASYNC_HOST_PORT_2:-8094}"
ASYNC_API_KEY="${API_KEY:-dev-key-change-me}"

up() {
  echo "==> starting async-redis-2 (host port ${ASYNC_HOST_PORT_2})"
  docker run -d --name async-redis-2 --network "$SANDBOX_NETWORK" \
    -p "${ASYNC_HOST_PORT_2}:8084" \
    -e MICRONAUT_ENVIRONMENTS=dev \
    -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e ASYNC_INSTANCE_ID=instance2 \
    -e ASYNC_REDIS_SECURITY_ENABLED=true \
    -e ASYNC_REDIS_SECURITY_API_KEYS="$ASYNC_API_KEY" \
    async-redis-service:local >/dev/null

  echo "==> waiting for async-redis-2 to become healthy"
  for i in $(seq 1 30); do
    sleep 2
    h=$(docker inspect -f '{{.State.Health.Status}}' async-redis-2 2>/dev/null || echo "?")
    if [ "$h" = "healthy" ]; then
      echo "==> async-redis-2=healthy"
      return 0
    fi
  done
  echo "!! async-redis-2=${h:-?} did not become healthy in time" >&2
  docker logs async-redis-2 --tail 40 >&2 || true
  return 1
}

down() {
  docker rm -f async-redis-2 >/dev/null 2>&1 || true
}

case "${1:-up}" in
  up) up ;;
  down) down ;;
  *) echo "usage: $0 [up|down]" >&2; exit 1 ;;
esac
