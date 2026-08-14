#!/usr/bin/env bash
# Shared helpers for the T57 capacity gate. Sourced by run_gate.sh and scenarios/*.sh —
# never executed directly.
set -uo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
CAPACITY_DIR="${REPO_ROOT}/load/capacity"
REPORTS_DIR="${REPO_ROOT}/load/reports"

SANDBOX_NETWORK="${SANDBOX_NETWORK:-payment-sandbox}"
PAYMENT_API_KEY="${PAYMENT_API_KEY:-dev-key-change-me}"
# AUD-30: second tenant key so the gate measures route capacity across >=2 tenants instead of one
# tenant's rate-limit bucket. Matches application.yml's default so a fresh sandbox works unset.
PAYMENT_API_KEY_2="${PAYMENT_API_KEY_2:-dev-key-2-change-me}"
JWT_SIGNATURE_SECRET="${JWT_SIGNATURE_SECRET:-dev-jwt-signature-secret-change-me-please-32-bytes}"
SBUS_DEV_JWT_SECRET="${SBUS_DEV_JWT_SECRET:-dev-jwt-signature-secret-change-me-please-32-bytes}"

if [ -f "${REPO_ROOT}/sandbox/.env" ]; then
  # shellcheck disable=SC1091
  set -a; source "${REPO_ROOT}/sandbox/.env"; set +a
fi
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in sandbox/.env}"

API_HOST_PORT_1="${API_HOST_PORT_1:-8080}"
API_HOST_PORT_2="${API_HOST_PORT_2:-8090}"
SBUS_HOST_PORT_1="${SBUS_HOST_PORT_1:-8081}"
SBUS_HOST_PORT_2="${SBUS_HOST_PORT_2:-8091}"
CORE_HOST_PORT="${CORE_HOST_PORT:-8082}"

# Resources applied identically to every payment-api / payment-sbus / core-mock container —
# see load/capacity/manifest.yaml#resources for the full rationale, including why 2.0 CPU
# (raised from an initial 1.0) did NOT fix the request timeouts seen under load — that turned
# out to be a real payment-api bug (task_3801253b), not a resource-budget problem.
API_CPUS="2.0"; API_MEM="768m"
SBUS_CPUS="2.0"; SBUS_MEM="768m"
CORE_CPUS="0.5"; CORE_MEM="384m"

log() { echo "[capacity] $*"; }

# ---- containers -------------------------------------------------------------------------

run_api() {
  local name="$1" host_port="$2" instances="$3"
  docker run -d --name "$name" --network "$SANDBOX_NETWORK" \
    --cpus "$API_CPUS" --memory "$API_MEM" \
    -p "${host_port}:8080" \
    -e MICRONAUT_ENVIRONMENTS=dev \
    -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    -e APICURIO_REGISTRY_URL=http://registry:8080/apis/registry/v2 \
    -e REDIS_HOST=redis -e REDIS_PORT=6379 \
    -e SBUS_BASE_URL=http://payment-sbus-1:8081 \
    -e PAYMENT_API_INSTANCES="$instances" \
    -e PAYMENT_API_KEY="$PAYMENT_API_KEY" \
    -e PAYMENT_API_KEY_2="$PAYMENT_API_KEY_2" \
    -e JWT_SIGNATURE_SECRET="$JWT_SIGNATURE_SECRET" \
    -e JWT_JWKS_URL= -e JWT_ISSUER= -e JWT_AUDIENCE= \
    payment-api:local >/dev/null
}

run_sbus() {
  local name="$1" host_port="$2"
  docker run -d --name "$name" --network "$SANDBOX_NETWORK" \
    --cpus "$SBUS_CPUS" --memory "$SBUS_MEM" \
    -p "${host_port}:8081" \
    -e MICRONAUT_ENVIRONMENTS=dev \
    -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    -e APICURIO_REGISTRY_URL=http://registry:8080/apis/registry/v2 \
    -e REDIS_URI=redis://redis:6379 \
    -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=sbus \
    -e POSTGRES_USER=sbus -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    -e SBUS_DEV_JWT_SECRET="$SBUS_DEV_JWT_SECRET" \
    -e SBUS_JWT_JWKS_URL= -e SBUS_JWT_ISSUER= -e SBUS_JWT_AUDIENCE= \
    payment-sbus:local >/dev/null
}

# run_core <latency_min_ms> <latency_max_ms> <decline_pct> <fail_pct>
run_core() {
  local lat_min="$1" lat_max="$2" decline="$3" fail="$4"
  docker run -d --name payment-core-mock --network "$SANDBOX_NETWORK" \
    --cpus "$CORE_CPUS" --memory "$CORE_MEM" \
    -p "${CORE_HOST_PORT}:8082" \
    -e MICRONAUT_ENVIRONMENTS=dev \
    -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    -e APICURIO_REGISTRY_URL=http://registry:8080/apis/registry/v2 \
    -e CORE_LATENCY_MIN_MS="$lat_min" -e CORE_LATENCY_MAX_MS="$lat_max" \
    -e CORE_DECLINE_PCT="$decline" -e CORE_FAIL_PCT="$fail" -e CORE_SEED=20260808 \
    payment-core-mock:local >/dev/null
}

wait_healthy() {
  local container="$1" max_tries="${2:-40}"
  for _ in $(seq 1 "$max_tries"); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)" = "healthy" ] && return 0
    sleep 3
  done
  log "!! ${container} did not become healthy in time"
  docker logs "$container" --tail 60 >&2 || true
  return 1
}

# bring_up <profile: certified-target|constrained-core>
bring_up() {
  local profile="$1"
  down >/dev/null 2>&1 || true

  case "$profile" in
    certified-target) run_core 1 2 10 0 ;; # see manifest.yaml#app_config.payment-core-mock
    constrained-core) run_core 20 20 10 0 ;;
    *) log "!! unknown profile: $profile"; return 1 ;;
  esac
  run_api payment-api-1 "$API_HOST_PORT_1" 2
  run_api payment-api-2 "$API_HOST_PORT_2" 2
  run_sbus payment-sbus-1 "$SBUS_HOST_PORT_1"
  run_sbus payment-sbus-2 "$SBUS_HOST_PORT_2"

  wait_healthy payment-core-mock && \
  wait_healthy payment-api-1 && \
  wait_healthy payment-api-2 && \
  wait_healthy payment-sbus-1 && \
  wait_healthy payment-sbus-2
}

down() {
  docker rm -f payment-api-1 payment-api-2 payment-sbus-1 payment-sbus-2 payment-core-mock >/dev/null 2>&1 || true
}

# reconfigure_core <latency_min_ms> <latency_max_ms> — used by the slowdown scenario to inject
# and later lift a transient dependency degradation without tearing down the rest of the fleet.
reconfigure_core() {
  local lat_min="$1" lat_max="$2"
  docker rm -f payment-core-mock >/dev/null 2>&1 || true
  run_core "$lat_min" "$lat_max" 10 0
  wait_healthy payment-core-mock
}

# warm_up — a freshly-started fleet's first requests pay for JIT warmup, Kafka producer/consumer
# metadata fetch, and Avro schema registration against the registry; observed locally to take
# some cold requests past 1s and starve the steady scenario's preallocated VUs before the
# JVMs settle. Run once per bring_up, discarded, so steady's timed window measures steady-state
# behavior instead of cold-start noise.
warm_up() {
  log "warm-up: 45s @ 10 req/s (discarded)"
  docker run --rm --network "$SANDBOX_NETWORK" -v "${REPO_ROOT}/load:/load" \
    -e BASE_URLS="http://payment-api-1:8080,http://payment-api-2:8080" \
    -e API_KEYS="${PAYMENT_API_KEY},${PAYMENT_API_KEY_2}" -e RATE=10 -e DURATION=45s \
    -e PRE_VUS=40 -e MAX_VUS=100 -e SCENARIO_LABEL=warmup -e SAMPLE_EVERY=1000000 \
    grafana/k6:0.54.0 run /load/k6/capacity.js > /dev/null 2>&1
}

# run_k6_scenario <profile> <scenario> <rate> <duration> <pre_vus> <max_vus> <sample_every>
# Runs k6 against the 2-instance API fleet, snapshotting metrics before/after and reconciling a
# sample of requestIds. Writes <REPORTS_DIR>/<profile>/<scenario>.{summary.json,stdout.log,
# before.json,after.json,reconcile.json}.
run_k6_scenario() {
  local profile="$1" scenario="$2" rate="$3" duration="$4" pre_vus="$5" max_vus="$6" sample_every="$7"
  local out="${REPORTS_DIR}/${profile}"
  mkdir -p "$out"

  # DRYRUN=1 shrinks every scenario to ~20s at a low rate, to validate the whole pipeline
  # (bring-up, k6, metrics, reconciliation, report) before committing to the full-duration gate.
  if [ "${DRYRUN:-0}" = "1" ]; then
    rate=$(( rate < 10 ? rate : 10 ))
    duration="20s"
    pre_vus=$(( pre_vus < 30 ? pre_vus : 30 ))
    max_vus=$(( max_vus < 60 ? max_vus : 60 ))
    sample_every=5
  fi

  log "${scenario} (${profile}): snapshotting before-state"
  "${CAPACITY_DIR}/collect_metrics.sh" before > "${out}/${scenario}.before.json"

  log "${scenario} (${profile}): running k6 (${rate} req/s x ${duration})"
  docker run --rm --network "$SANDBOX_NETWORK" -v "${REPO_ROOT}/load:/load" \
    -e BASE_URLS="http://payment-api-1:8080,http://payment-api-2:8080" \
    -e API_KEYS="${PAYMENT_API_KEY},${PAYMENT_API_KEY_2}" -e RATE="$rate" -e DURATION="$duration" \
    -e PRE_VUS="$pre_vus" -e MAX_VUS="$max_vus" -e SCENARIO_LABEL="$scenario" -e SAMPLE_EVERY="$sample_every" \
    grafana/k6:0.54.0 run --summary-export="/load/reports/${profile}/${scenario}.summary.json" \
    /load/k6/capacity.js > "${out}/${scenario}.stdout.txt" 2>&1
  log "${scenario} (${profile}): k6 exit=$?"

  log "${scenario} (${profile}): snapshotting after-state"
  "${CAPACITY_DIR}/collect_metrics.sh" after > "${out}/${scenario}.after.json"

  log "${scenario} (${profile}): reconciling sampled requestIds"
  python3 "${CAPACITY_DIR}/reconcile.py" "${out}/${scenario}.stdout.txt" \
    "http://localhost:${API_HOST_PORT_1}" "$PAYMENT_API_KEY" 40 > "${out}/${scenario}.reconcile.json"
}

# ---- auth tokens --------------------------------------------------------------------------

api_token() {
  curl -s -X POST "http://localhost:${API_HOST_PORT_1}/auth/token" \
    -H 'Content-Type: application/json' \
    -d '{"userId":"capacity-gate","groups":["ROLE_ADMIN"]}' | jq -r '.accessToken'
}

sbus_token() {
  python3 "${CAPACITY_DIR}/mint_jwt.py" "$SBUS_DEV_JWT_SECRET"
}

# ---- metrics --------------------------------------------------------------------------

# prometheus_metric <url> <token> <metric_name> [label_filter] -> sums matching sample values
prometheus_metric() {
  local url="$1" token="$2" metric="$3" filter="${4:-}"
  local raw
  raw=$(curl -s -m 10 -H "Authorization: Bearer ${token}" "$url" 2>/dev/null)
  # Anchor on a word boundary (space or `{`) right after the metric name — a naive prefix match
  # would also catch e.g. "sbus_dlq_unconfirmed_oldest_age_seconds" when asked for
  # "sbus_dlq_unconfirmed".
  echo "$raw" | awk -v m="^${metric}[ {]" -v f="$filter" '
    $0 ~ m {
      if (f == "" || $0 ~ f) {
        v = $NF
        if (v ~ /^[0-9.eE+-]+$/) { sum += v; found = 1 }
      }
    }
    END { if (found) printf "%.4f\n", sum; else print "0" }
  '
}

redis_info_field() {
  docker exec payment-sandbox-redis-1 redis-cli INFO 2>/dev/null | grep "^${1}:" | cut -d: -f2 | tr -d '\r'
}

pg_stat() {
  docker exec -i payment-sandbox-postgres-1 psql -U sbus -d sbus -tA -c "$1" 2>/dev/null
}
