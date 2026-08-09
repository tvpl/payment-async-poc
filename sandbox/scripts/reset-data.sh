#!/usr/bin/env bash
set -euo pipefail

CONFIRMATION="--confirm-destroy-sandbox-data"
if [[ "${1:-}" != "$CONFIRMATION" || $# -ne 1 ]]; then
  echo "Refusing destructive reset. Re-run with: $0 $CONFIRMATION" >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/compose.yml" -f "$ROOT_DIR/compose.profiles.yml")
VOLUMES=(
  "${KAFKA_VOLUME:-payment-sandbox-kafka}"
  "${REDIS_VOLUME:-payment-sandbox-redis}"
  "${POSTGRES_VOLUME:-payment-sandbox-postgres}"
  "${PROMETHEUS_VOLUME:-payment-sandbox-prometheus}"
  "${GRAFANA_VOLUME:-payment-sandbox-grafana}"
)

"${COMPOSE[@]}" --profile observability --profile tools down
for volume in "${VOLUMES[@]}"; do
  docker volume rm "$volume"
done

echo "sandbox-reset: removed only declared sandbox volumes"
