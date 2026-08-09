#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/compose.yml")
REGISTRY_URL="${REGISTRY_URL:-http://localhost:${REGISTRY_HOST_PORT:-8085}/apis/registry/v2}"
ONLY=""
SKIP_INIT=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --list)
      printf '%s\n' kafka.metadata kafka.topic redis.ping redis.roundtrip postgres.query postgres.transaction registry.info registry.rule registry.schema
      exit 0
      ;;
    --only)
      ONLY="${2:?--only requires kafka, redis, postgres or registry}"
      shift 2
      ;;
    --skip-init)
      SKIP_INIT=true
      shift
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

: "${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in sandbox/.env or the environment}"

if [[ "$SKIP_INIT" == false ]]; then
  "$ROOT_DIR/smoke/init.sh" >/dev/null || {
    echo "[FAIL] initialization: inspect docker compose logs for the unavailable dependency" >&2
    exit 1
  }
fi

failures=0

probe() {
  local dependency="$1"
  local name="$2"
  shift 2
  if [[ -n "$ONLY" && "$ONLY" != "$dependency" ]]; then
    return
  fi
  if "$@" >/dev/null 2>&1; then
    echo "[PASS] $dependency: $name"
  else
    echo "[FAIL] $dependency: $name; inspect: docker compose -f $ROOT_DIR/compose.yml logs $dependency" >&2
    failures=$((failures + 1))
  fi
}

probe kafka metadata "${COMPOSE[@]}" exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
probe kafka topic "${COMPOSE[@]}" exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic payment.simulation.requested

probe redis ping "${COMPOSE[@]}" exec -T redis redis-cli PING
probe redis roundtrip "${COMPOSE[@]}" exec -T redis sh -ec \
  'key=sandbox:smoke:roundtrip; redis-cli SET "$key" ready EX 30 >/dev/null; test "$(redis-cli GET "$key")" = ready; redis-cli DEL "$key" >/dev/null'

probe postgres query "${COMPOSE[@]}" exec -T postgres psql \
  --username "${POSTGRES_USER:-sbus}" --dbname "${POSTGRES_DB:-sbus}" \
  --tuples-only --command 'SELECT 1'
probe postgres transaction "${COMPOSE[@]}" exec -T postgres psql \
  --username "${POSTGRES_USER:-sbus}" --dbname "${POSTGRES_DB:-sbus}" \
  --set ON_ERROR_STOP=1 --command \
  'BEGIN; CREATE TEMP TABLE sandbox_smoke(value integer); INSERT INTO sandbox_smoke VALUES (1); ROLLBACK;'

probe registry info curl -fsS "$REGISTRY_URL/system/info"
probe registry rule curl -fsS "$REGISTRY_URL/admin/rules/COMPATIBILITY"
probe registry schema curl -fsS \
  "$REGISTRY_URL/groups/sandbox/artifacts/sandbox-smoke"

if [[ "$failures" -gt 0 ]]; then
  echo "sandbox-smoke: FAILED ($failures probe(s))" >&2
  exit 1
fi

echo "sandbox-smoke: READY"
