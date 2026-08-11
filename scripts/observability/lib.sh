#!/usr/bin/env bash
# Shared helpers for T58's observability verification. Sourced by verify.sh and checks/*.sh —
# never executed directly.
set -uo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

SANDBOX_NETWORK="${SANDBOX_NETWORK:-payment-sandbox}"
PAYMENT_API_KEY="${PAYMENT_API_KEY:-dev-key-change-me}"
JWT_SIGNATURE_SECRET="${JWT_SIGNATURE_SECRET:-dev-jwt-signature-secret-change-me-please-32-bytes}"
SBUS_DEV_JWT_SECRET="${SBUS_DEV_JWT_SECRET:-dev-jwt-signature-secret-change-me-please-32-bytes}"

if [ -f "${REPO_ROOT}/sandbox/.env" ]; then
  set -a; source "${REPO_ROOT}/sandbox/.env"; set +a
fi
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in sandbox/.env}"

log() { echo "[obs] $*"; }

# Guarded, not a plain assignment: verify.sh sources this once, then sources each checks/*.sh in
# the SAME shell so tallies accumulate across checks — a plain `PASS_COUNT=0` here would reset
# the running total back to zero every time a check script re-sources lib.sh.
: "${PASS_COUNT:=0}"
: "${FAIL_COUNT:=0}"
[ -z "${FAILED_CHECKS+x}" ] && FAILED_CHECKS=()

check_pass() { PASS_COUNT=$((PASS_COUNT + 1)); log "PASS: $1"; }
check_fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); FAILED_CHECKS+=("$1"); log "FAIL: $1 — $2"; }

# ---- bring-up (each app's own `docker compose up`, single instance) ----------------------
#
# Uses each app's own compose.yaml directly instead of ad-hoc `docker run` — container names
# then match exactly what sandbox/observability/application-targets.json already declares
# (payment-core-mock-core-mock-1, payment-sbus-sbus-1, payment-api-api-1), and every app's
# compose default already resolves the others by short DNS name on the shared external
# `sandbox` network (kafka, redis, sbus, ...) — only the undefaulted secrets need exporting.

export SANDBOX_NETWORK PAYMENT_API_KEY JWT_SIGNATURE_SECRET SBUS_DEV_JWT_SECRET POSTGRES_PASSWORD

bring_up() {
  down >/dev/null 2>&1 || true
  ( cd "${REPO_ROOT}/payment-core-mock" && docker compose up -d --wait ) || return 1
  ( cd "${REPO_ROOT}/payment-sbus" && docker compose up -d --wait ) || return 1
  ( cd "${REPO_ROOT}/payment-api" && docker compose up -d --wait ) || return 1
}

down() {
  ( cd "${REPO_ROOT}/payment-api" && docker compose down ) >/dev/null 2>&1 || true
  ( cd "${REPO_ROOT}/payment-sbus" && docker compose down ) >/dev/null 2>&1 || true
  ( cd "${REPO_ROOT}/payment-core-mock" && docker compose down ) >/dev/null 2>&1 || true
  docker rm -f obs-prometheus >/dev/null 2>&1 || true
}

# ---- auth tokens (see load/capacity/lib.sh — same approach) -------------------------------

api_token() {
  curl -s -X POST "http://localhost:8080/auth/token" \
    -H 'Content-Type: application/json' \
    -d '{"userId":"obs-check","groups":["ROLE_ADMIN"]}' | jq -r '.accessToken'
}

sbus_token() {
  python3 "${REPO_ROOT}/load/capacity/mint_jwt.py" "$SBUS_DEV_JWT_SECRET"
}

# ---- traffic ------------------------------------------------------------------------------

# submit_payment -> prints "HTTP_STATUS\nBODY"
submit_payment() {
  local idem="${1:-obs-$(date +%s)-$RANDOM}"
  local body_file http_status
  body_file=$(mktemp)
  http_status=$(curl -s -o "$body_file" -w '%{http_code}' -m 10 \
    -X POST "http://localhost:8080/payment-simulations" \
    -H 'Content-Type: application/json' \
    -H "X-API-Key: ${PAYMENT_API_KEY}" \
    -H "Idempotency-Key: ${idem}" \
    -d '{"merchantId":"MERCHANT-001","amount":42.00,"currency":"BRL","paymentMethod":"CREDIT_CARD","brand":"VISA","installments":1,"captureMode":"AUTHORIZE_AND_CAPTURE"}')
  printf '%s\n' "$http_status"
  cat "$body_file"
  rm -f "$body_file"
}
