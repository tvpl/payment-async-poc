#!/bin/sh
set -eu

boundary_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$boundary_root"

python3 -m unittest discover -s deploy -p 'test_*.py'
docker compose --env-file .env.example -f compose.yaml config -q

if [ "${1:-}" = "--structural" ]; then
  echo "async-redis-release-package: PASS (structural)"
  exit 0
fi

if ! docker info >/dev/null 2>&1; then
  echo "async-redis-release-package: NOT_RUN (Docker unavailable)"
  exit 0
fi

project_name="async-redis-service-t45"
cleanup() {
  docker compose --project-name "$project_name" --env-file .env.example \
    -f compose.yaml down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

docker compose --project-name "$project_name" --env-file .env.example \
  -f compose.yaml up --build --detach --wait --wait-timeout 120 async-redis
container_id="$(docker compose --project-name "$project_name" --env-file .env.example -f compose.yaml ps -q async-redis)"
test -n "$container_id"
test "$(docker inspect --format '{{.Config.User}}' "$container_id")" = "10001:10001"
test "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$container_id")" = "true"
docker inspect --format '{{json .NetworkSettings.Networks}}' "$container_id" | grep -q 'payment-sandbox'
echo "async-redis-release-package: PASS"
