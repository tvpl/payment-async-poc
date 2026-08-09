#!/bin/sh
set -eu

project_name="payment-core-mock-t22"
compose_file="compose.yaml"

cleanup() {
  docker compose --project-name "$project_name" --env-file .env.example \
    -f "$compose_file" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

python3 -m unittest discover -s deploy -p 'test_*.py'
docker compose --env-file .env.example -f "$compose_file" config -q
docker compose --project-name "$project_name" --env-file .env.example \
  -f "$compose_file" up --build --detach --wait --wait-timeout 90 core-mock

container_id="$(docker compose --project-name "$project_name" --env-file .env.example \
  -f "$compose_file" ps -q core-mock)"

test -n "$container_id"
test "$(docker inspect --format '{{.Config.User}}' "$container_id")" = "10001:10001"
test "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$container_id")" = "true"
test "$(docker inspect --format '{{index .Config.Labels "com.example.lifecycle"}}' "$container_id")" = "NON_PRODUCTION"
docker inspect --format '{{json .NetworkSettings.Networks}}' "$container_id" | grep -q 'payment-sandbox'

echo "container-package: PASS"
