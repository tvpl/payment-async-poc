#!/usr/bin/env bash
set -uo pipefail

PROMETHEUS_HOST_PORT="${PROMETHEUS_HOST_PORT:-9090}"
JAEGER_HOST_PORT="${JAEGER_HOST_PORT:-16686}"
GRAFANA_HOST_PORT="${GRAFANA_HOST_PORT:-3000}"
KAFKA_UI_HOST_PORT="${KAFKA_UI_HOST_PORT:-8088}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:$PROMETHEUS_HOST_PORT}"
JAEGER_URL="${JAEGER_URL:-http://localhost:$JAEGER_HOST_PORT}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:$GRAFANA_HOST_PORT}"
KAFKA_UI_URL="${KAFKA_UI_URL:-http://localhost:$KAFKA_UI_HOST_PORT}"
failures=0

probe() {
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "[PASS] $name"
  else
    echo "[FAIL] $name" >&2
    failures=$((failures + 1))
  fi
}

probe prometheus.ready curl -fsS "$PROMETHEUS_URL/-/ready"
probe prometheus.query curl -fsS --get "$PROMETHEUS_URL/api/v1/query" --data-urlencode 'query=up'
probe jaeger.services curl -fsS "$JAEGER_URL/api/services"
probe grafana.health curl -fsS "$GRAFANA_URL/api/health"
probe kafka-ui.http curl -fsS "$KAFKA_UI_URL/actuator/health"

if [[ "$failures" -gt 0 ]]; then
  echo "sandbox-profile-smoke: FAILED ($failures probe(s))" >&2
  exit 1
fi

echo "sandbox-profile-smoke: READY"
