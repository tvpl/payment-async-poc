#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/compose.yml")
TOPICS_FILE="$ROOT_DIR/config/kafka/topics.txt"
REGISTRY_URL="${REGISTRY_URL:-http://localhost:${REGISTRY_HOST_PORT:-8085}/apis/registry/v2}"
SCHEMA_FILE="$ROOT_DIR/smoke/fixtures/sandbox-smoke.avsc"

: "${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in sandbox/.env or the environment}"

while IFS= read -r topic; do
  if [[ -z "$topic" || "$topic" == \#* ]]; then
    continue
  fi
  "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "${KAFKA_TOPIC_PARTITIONS:-3}" \
    --replication-factor "${KAFKA_TOPIC_RF:-1}" >/dev/null
done < "$TOPICS_FILE"

if ! curl -fsS "$REGISTRY_URL/admin/rules/COMPATIBILITY" >/dev/null 2>&1; then
  curl -fsS -X POST "$REGISTRY_URL/admin/rules" \
    -H 'Content-Type: application/json' \
    -d '{"type":"COMPATIBILITY","config":"FULL"}' >/dev/null
fi

if ! curl -fsS "$REGISTRY_URL/groups/sandbox/artifacts/sandbox-smoke/meta" >/dev/null 2>&1; then
  curl -fsS -X POST \
    "$REGISTRY_URL/groups/sandbox/artifacts" \
    -H 'Content-Type: application/json; artifactType=AVRO' \
    -H 'X-Registry-ArtifactId: sandbox-smoke' \
    --data-binary "@$SCHEMA_FILE" >/dev/null
fi

"${COMPOSE[@]}" exec -T postgres psql \
  --username "${POSTGRES_USER:-sbus}" \
  --dbname "${POSTGRES_DB:-sbus}" \
  --set ON_ERROR_STOP=1 \
  --command 'CREATE SCHEMA IF NOT EXISTS sandbox_support' >/dev/null

echo "sandbox-init: READY"
