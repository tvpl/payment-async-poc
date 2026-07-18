#!/usr/bin/env bash
# Guided walkthrough of the feature-control library via the feature-demo service (:8083).
#
# Exercises all four scenarios with curl, mints a dev JWT, and flips a flag at runtime to show the
# decision change live. Read top-to-bottom to learn the API each of the 30+ apps would use.
#
#   ./scripts/demo-features.sh
#   BASE_URL=http://localhost:8083 ./scripts/demo-features.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8083}"

json_field() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$1" | jq -r "${2} // empty"
  else
    printf '%s' "$1" | grep -oE "\"$(basename "$2")\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
      | head -1 | sed -E "s/.*:[[:space:]]*\"([^\"]*)\"/\1/"
  fi
}

hr() { printf '\n\033[36m== %s ==\033[0m\n' "$1"; }

hr "1) Feature toggle — 100% to service A or B (GET /demo/toggle)"
curl -s "${BASE_URL}/demo/toggle"; echo

hr "2) A/B rollout — sticky per user; two different anon ids may land differently"
curl -s -H 'X-Anon-Id: user-aaa' "${BASE_URL}/demo/ab"; echo
curl -s -H 'X-Anon-Id: user-zzz' "${BASE_URL}/demo/ab"; echo
echo "   (repeat with the same X-Anon-Id -> identical variant every time)"

hr "3) Mint a dev JWT for a user in the 'beta' and 'v0-testers' groups"
TOKEN_JSON=$(curl -s -X POST "${BASE_URL}/auth/token" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"alice","groups":["beta","v0-testers"]}')
TOKEN=$(json_field "$TOKEN_JSON" .accessToken)
echo "   token acquired (${#TOKEN} chars)"

hr "3a) Restricted feature — allowed WITH the token, denied WITHOUT (403)"
curl -s -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/demo/restricted"; echo
curl -s -o /dev/null -w '   anonymous -> HTTP %{http_code}\n' "${BASE_URL}/demo/restricted"

hr "4) API v0 — served to the v0-testers group, others get v1"
curl -s -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/demo/version"; echo
curl -s "${BASE_URL}/demo/version"; echo
echo "   frontend hitting /v0/demo explicitly:"
curl -s -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/v0/demo"; echo

hr "5) Kafka topic A/B routing from a flag (GET /demo/topic)"
curl -s "${BASE_URL}/demo/topic"; echo

hr "6) Runtime flip — turn demo-toggle OFF via the admin API, then re-check"
echo "   (admin endpoints require ROLE_ADMIN — minting an admin token)"
ADMIN_JSON=$(curl -s -X POST "${BASE_URL}/auth/token" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"admin-user","groups":["ROLE_ADMIN"]}')
ADMIN=$(json_field "$ADMIN_JSON" .accessToken)
curl -s -X PUT "${BASE_URL}/admin/features/demo-toggle" \
  -H "Authorization: Bearer ${ADMIN}" -H 'Content-Type: application/json' \
  -d '{"name":"demo-toggle","type":"BOOLEAN","enabled":false,"onVariant":"service-b","offVariant":"service-a"}' >/dev/null
echo "   after flip:"; curl -s "${BASE_URL}/demo/toggle"; echo
curl -s -X DELETE "${BASE_URL}/admin/features/demo-toggle" -H "Authorization: Bearer ${ADMIN}" >/dev/null
echo "   after removing the override (baseline restored):"; curl -s "${BASE_URL}/demo/toggle"; echo

hr "Done"
