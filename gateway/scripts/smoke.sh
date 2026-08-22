#!/usr/bin/env bash
# Smoke E2E através do gateway: token no Keycloak -> Envoy -> Edge -> Sbus -> Core.
#
# Pré-requisitos: sandbox up, payment-api/payment-sbus/payment-core-mock up, gateway up.
#   PAYMENT_API_KEY  chave aceita pelo Edge (a mesma do payment-api/.env), com
#                     payment.security.tenants vinculando seu hash a ["tenant-a"] -
#                     o mesmo tenant_id do usuário $KC_USER no realm (TEN-07/K8S-04)
#   GATEWAY_URL      default http://localhost:10000
#   KEYCLOAK_URL     default http://localhost:8086
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:10000}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8086}"
PAYMENT_API_KEY="${PAYMENT_API_KEY:?set PAYMENT_API_KEY (a mesma chave do payment-api/.env)}"
KC_USER="${KC_USER:-alice}"
KC_PASSWORD="${KC_PASSWORD:-alice-change-me}"

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }

echo "==> 1. token no Keycloak (password grant, client payments-cli)"
TOKEN=$(curl -fsS -X POST "$KEYCLOAK_URL/realms/payments/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' -d 'client_id=payments-cli' \
  -d "username=$KC_USER" -d "password=$KC_PASSWORD" | jq -r '.access_token')
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || fail "não obteve access_token do Keycloak"
echo "    token OK"

echo "==> 2. sem token deve ser barrado no gateway (401)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY_URL/payment-simulations" \
  -H 'Content-Type: application/json' -H "X-API-Key: $PAYMENT_API_KEY" -d '{}')
[ "$CODE" = "401" ] || fail "esperava 401 sem token, recebeu $CODE"
echo "    401 OK"

echo "==> 3. rota não exposta deve retornar 404 do gateway"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
  "$GATEWAY_URL/admin/features/x")
[ "$CODE" = "404" ] || fail "esperava 404 em rota não exposta, recebeu $CODE"
echo "    404 OK"

echo "==> 4. POST /payment-simulations com token + X-API-Key"
IDEM="smoke-gw-$(date +%s)-$RANDOM"
BODY='{"merchantId":"m-gateway-smoke","amount":150.75,"currency":"BRL","paymentMethod":"CREDIT","brand":"VISA","installments":1,"captureMode":"AUTO"}'
RESP=$(curl -s -w '\n%{http_code}' -X POST "$GATEWAY_URL/payment-simulations" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Key: $PAYMENT_API_KEY" \
  -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' -d "$BODY")
CODE=$(echo "$RESP" | tail -n1)
PAYLOAD=$(echo "$RESP" | head -n -1)
case "$CODE" in
  200|202|422) echo "    POST OK ($CODE)" ;;
  *) fail "POST retornou $CODE: $PAYLOAD" ;;
esac
REQUEST_ID=$(echo "$PAYLOAD" | jq -r '.requestId // empty')
[ -n "$REQUEST_ID" ] || fail "resposta sem requestId: $PAYLOAD"

echo "==> 5. GET /payment-simulations/$REQUEST_ID até desfecho terminal"
for i in $(seq 1 20); do
  STATUS=$(curl -fsS -H "Authorization: Bearer $TOKEN" -H "X-API-Key: $PAYMENT_API_KEY" \
    "$GATEWAY_URL/payment-simulations/$REQUEST_ID" | jq -r '.status')
  echo "    status=$STATUS (tentativa $i)"
  case "$STATUS" in COMPLETED|FAILED) break ;; esac
  sleep 1
done
case "$STATUS" in
  COMPLETED|FAILED) echo "    desfecho terminal OK" ;;
  *) fail "status não terminal após polling: $STATUS" ;;
esac

echo "==> 6. health passa sem token pelo gateway"
curl -fsS "$GATEWAY_URL/health/liveness" >/dev/null || fail "health via gateway falhou"
echo "    health OK"

echo "==> 7. X-Tenant-Id forjado pelo cliente é sobrescrito pelo gateway (TEN-07/K8S-04)"
IDEM_FORGED="smoke-gw-tenant-$(date +%s)-$RANDOM"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY_URL/payment-simulations" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Key: $PAYMENT_API_KEY" \
  -H "X-Tenant-Id: forged-tenant-should-be-overwritten" \
  -H "Idempotency-Key: $IDEM_FORGED" -H 'Content-Type: application/json' -d "$BODY")
# Se o gateway não sobrescrevesse o header, o Edge veria um tenant sem binding
# para esta API key e responderia 403 (TEN-01). Um código igual ao do passo 4
# prova que o claim_to_headers do Envoy substituiu o valor forjado.
[ "$CODE" != "403" ] || fail "X-Tenant-Id forjado não foi sobrescrito: Edge respondeu 403"
case "$CODE" in
  200|202|422) echo "    header forjado sobrescrito OK ($CODE)" ;;
  *) fail "POST com header forjado retornou $CODE inesperado: $CODE" ;;
esac

echo "SMOKE PASS: gateway -> edge -> sbus -> core"
