#!/usr/bin/env bash
# design.md §7.1 / T58 Done-when: a critical alert fires synthetically. Forces a real
# PaymentApiResponseDeadLettered condition (poison payment.simulation.completed the same way T55
# poisons payment.simulation.requested — PaymentResponseConsumer.decode() throws on a
# non-Avro payload and dead-letters immediately, PaymentResponseConsumer.java:93-95, no retry
# delay) against a scratch Prometheus carrying payment-api's real alert rule
# (payment-api/ops/alerts/api-admission-and-dlq.yml, `for: 0m` — fires on the first evaluation
# where the condition holds). The scratch Prometheus is short-lived and uses a freshly minted
# token (bounded scope, see manifest note on the scrape-credential gap); it proves the mechanism
# — rule syntax, threshold, and the metric it depends on — is real and correct.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

# Wrapped in a function so `trap ... RETURN` cleans up right when THIS check finishes, even
# though verify.sh sources (not subprocesses) every checks/*.sh — a plain `trap ... EXIT` here
# would only fire when verify.sh's whole process exits, leaving obs-prometheus running for the
# rest of the run.
check_alert_fires_synthetically() {
  local scratch_dir token prom_port firing resp state first_seen value
  scratch_dir=$(mktemp -d)
  # Double-quoted so $scratch_dir is expanded NOW, embedding the literal path in the trap
  # command — a RETURN trap fires after the function's `local`s are already torn down, so a
  # single-quoted (deferred-expansion) trap referencing $scratch_dir would see it unbound.
  trap "rm -rf '${scratch_dir}'; docker rm -f obs-prometheus >/dev/null 2>&1 || true" RETURN

  token=$(api_token)
  if [ -z "$token" ] || [ "$token" = "null" ]; then
    check_fail "critical alert fires synthetically" "could not mint an API token for the scratch Prometheus scrape"
    return 0
  fi

  cat > "${scratch_dir}/prometheus.yml" <<EOF
global:
  scrape_interval: 3s
  evaluation_interval: 3s
rule_files:
  - /etc/prometheus/rules/payment-api-alerts.yml
scrape_configs:
  - job_name: payment-simulation-api
    metrics_path: /prometheus
    authorization:
      credentials: ${token}
    static_configs:
      - targets: [payment-api-api-1:8080]
EOF

  prom_port="${OBS_PROMETHEUS_PORT:-19090}"
  docker run -d --name obs-prometheus --network "$SANDBOX_NETWORK" \
    -p "${prom_port}:9090" \
    -v "${scratch_dir}/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
    -v "${REPO_ROOT}/payment-api/ops/alerts/api-admission-and-dlq.yml:/etc/prometheus/rules/payment-api-alerts.yml:ro" \
    prom/prometheus:v2.53.0@sha256:075b1ba2c4ebb04bc3a6ab86c06ec8d8099f8fda1c96ef6d104d9bb1def1d8bc \
    --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.path=/prometheus >/dev/null

  for _ in $(seq 1 15); do
    status=$(curl -s -m 3 "http://localhost:${prom_port}/-/ready" 2>/dev/null)
    [ -n "$status" ] && break
    sleep 1
  done

  # `api_response_dead_lettered_total` is a Micrometer counter with a dynamic `stage` tag
  # (ApiMetrics.java:76-78) — it does not exist in Prometheus AT ALL until the first dead-letter
  # ever happens, so there is no way to get a pre-existing "0 baseline" sample for it (unlike a
  # normal always-registered counter). And `increase()` needs at least TWO samples of the SAME
  # series within its window to compute anything — a series that just came into existence with
  # one sample produces no result. So: poison once (creates the series at value=1), wait for the
  # scratch Prometheus to actually capture that first sample, poison AGAIN (value=2, a real
  # observed increase across two samples of an existing series), then wait for firing.
  poison_completed_topic() {
    local poison_key="obs-alert-$(date +%s)-$RANDOM"
    echo "${poison_key}:THIS_IS_NOT_VALID_AVRO_${RANDOM}" | docker exec -i payment-sandbox-kafka-1 \
      /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
      --topic payment.simulation.completed \
      --property parse.key=true --property key.separator=: >/dev/null 2>&1
  }

  log "poisoning payment.simulation.completed (1/2) to create api_response_dead_lettered_total"
  poison_completed_topic

  first_seen=""
  for _ in $(seq 1 15); do
    value=$(curl -s -m 3 "http://localhost:${prom_port}/api/v1/query" \
      --data-urlencode 'query=api_response_dead_lettered_total' 2>/dev/null \
      | jq -r '.data.result[0].value[1] // empty' 2>/dev/null)
    [ -n "$value" ] && { first_seen="yes"; break; }
    sleep 1
  done
  if [ -z "$first_seen" ]; then
    check_fail "critical alert fires synthetically" "api_response_dead_lettered_total never appeared in the scratch Prometheus after the first poison"
    return 0
  fi

  log "poisoning payment.simulation.completed (2/2) to observe a real increase"
  poison_completed_topic

  firing=""
  for _ in $(seq 1 20); do
    resp=$(curl -s -m 5 "http://localhost:${prom_port}/api/v1/rules" 2>/dev/null)
    state=$(echo "$resp" | jq -r '.data.groups[].rules[] | select(.name=="PaymentApiResponseDeadLettered") | .state' 2>/dev/null)
    if [ "$state" = "firing" ]; then
      firing="yes"
      break
    fi
    sleep 3
  done

  if [ -n "$firing" ]; then
    check_pass "PaymentApiResponseDeadLettered fires synthetically (real alert rule, real metric, real trigger)"
  else
    check_fail "PaymentApiResponseDeadLettered fires synthetically" \
      "alert never reached state=firing (last query: ${resp:-<none>})"
  fi
}

check_alert_fires_synthetically
