#!/usr/bin/env bash
# CAP-04/SBX-05: the sandbox's own (real, persistent) Prometheus must actually scrape at least
# one real application-owned target via application-targets.json's file_sd — not just have the
# file wired, but show a live `up==1` sample.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

PROM_URL="${PROM_URL:-http://localhost:9090}"

up=""
for _ in $(seq 1 15); do
  resp=$(curl -s -m 5 "${PROM_URL}/api/v1/query" --data-urlencode 'query=up{job="payment-core-mock"}' 2>/dev/null)
  value=$(echo "$resp" | jq -r '.data.result[0].value[1] // empty' 2>/dev/null)
  if [ "$value" = "1" ]; then
    up="yes"
    break
  fi
  sleep 2
done

if [ -n "$up" ]; then
  check_pass "sandbox Prometheus scrapes payment-core-mock via application-targets.json (up==1)"
else
  check_fail "sandbox Prometheus scrapes payment-core-mock via application-targets.json" \
    "up{job=\"payment-core-mock\"} never reported 1 — check application-targets.json / container name / network"
fi

# api/sbus/async-redis-service targets are also registered (application-targets.json) but are
# EXPECTED to show up==0 right now — /prometheus requires an authenticated bearer token and no
# long-lived scrape credential is provisioned (documented gap, application-assets.json). Confirm
# that expectation instead of silently ignoring it, so a future fix flips this from expected-0 to
# a real regression if it ever silently breaks again.
resp=$(curl -s -m 5 "${PROM_URL}/api/v1/query" --data-urlencode 'query=up{job="payment-simulation-api"}' 2>/dev/null)
api_up=$(echo "$resp" | jq -r '.data.result[0].value[1] // "none"' 2>/dev/null)
if [ "$api_up" = "0" ]; then
  check_pass "payment-simulation-api scrape target registered, fails as expected (auth gap, documented)"
else
  check_fail "payment-simulation-api scrape target registered, fails as expected (auth gap, documented)" \
    "expected up==0 (known auth gap) but got '${api_up}' — investigate before trusting this as a stable expectation"
fi
