#!/usr/bin/env bash
# Brings up a second payment-api and payment-sbus container (api-2 / sbus-2) alongside the
# primary instances T54 already started, so CAP-05 can be proven against a real fleet instead
# of a single process. Uses `docker run` directly (not `docker compose up --scale`) because the
# primary compose.yaml binds a fixed host port per service, which --scale cannot share.
set -euo pipefail

SANDBOX_NETWORK="${SANDBOX_NETWORK:-payment-sandbox}"
API_HOST_PORT_2="${API_HOST_PORT_2:-8090}"
SBUS_HOST_PORT_2="${SBUS_HOST_PORT_2:-8091}"
PAYMENT_API_KEY="${PAYMENT_API_KEY:-dev-key-change-me}"
JWT_SIGNATURE_SECRET="${JWT_SIGNATURE_SECRET:-dev-jwt-signature-secret-change-me-please-32-bytes}"
SBUS_DEV_JWT_SECRET="${SBUS_DEV_JWT_SECRET:-dev-jwt-signature-secret-change-me-please-32-bytes}"

up() {
  local POSTGRES_PASSWORD="${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD (see sandbox/.env)}"
  echo "==> starting payment-api-2 (host port ${API_HOST_PORT_2})"
  docker run -d --name payment-api-2 --network "$SANDBOX_NETWORK" \
    -p "${API_HOST_PORT_2}:8080" \
    -e MICRONAUT_ENVIRONMENTS=dev \
    -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    -e APICURIO_REGISTRY_URL=http://registry:8080/apis/registry/v2 \
    -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e SBUS_BASE_URL=http://sbus:8081 \
    -e PAYMENT_API_INSTANCES=2 \
    -e PAYMENT_API_KEY="$PAYMENT_API_KEY" \
    -e JWT_SIGNATURE_SECRET="$JWT_SIGNATURE_SECRET" \
    -e JWT_JWKS_URL= -e JWT_ISSUER= -e JWT_AUDIENCE= \
    payment-api:local >/dev/null

  echo "==> starting payment-sbus-2 (host port ${SBUS_HOST_PORT_2})"
  docker run -d --name payment-sbus-2 --network "$SANDBOX_NETWORK" \
    -p "${SBUS_HOST_PORT_2}:8081" \
    -e MICRONAUT_ENVIRONMENTS=dev \
    -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    -e APICURIO_REGISTRY_URL=http://registry:8080/apis/registry/v2 \
    -e REDIS_URI=redis://redis:6379 \
    -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=sbus \
    -e POSTGRES_USER=sbus -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    -e SBUS_DEV_JWT_SECRET="$SBUS_DEV_JWT_SECRET" \
    -e SBUS_JWT_JWKS_URL= -e SBUS_JWT_ISSUER= -e SBUS_JWT_AUDIENCE= \
    payment-sbus:local >/dev/null

  echo "==> waiting for both to become healthy"
  for i in $(seq 1 30); do
    sleep 2
    a=$(docker inspect -f '{{.State.Health.Status}}' payment-api-2 2>/dev/null || echo "?")
    s=$(docker inspect -f '{{.State.Health.Status}}' payment-sbus-2 2>/dev/null || echo "?")
    if [ "$a" = "healthy" ] && [ "$s" = "healthy" ]; then
      echo "==> api-2=healthy sbus-2=healthy"
      return 0
    fi
  done
  echo "!! api-2=${a:-?} sbus-2=${s:-?} did not become healthy in time" >&2
  docker logs payment-api-2 --tail 40 >&2 || true
  docker logs payment-sbus-2 --tail 40 >&2 || true
  return 1
}

down() {
  docker rm -f payment-api-2 payment-sbus-2 >/dev/null 2>&1 || true
}

case "${1:-up}" in
  up) up ;;
  down) down ;;
  *) echo "usage: $0 [up|down]" >&2; exit 1 ;;
esac
