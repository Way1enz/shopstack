#!/usr/bin/env bash
set -euo pipefail

# Black-box check against a stack already running via `docker compose up --build`,
# same checks as CI's smoke-test job. Update the field names below if a DTO changes.
#
# Usage: scripts/smoke-test.sh
#        BASE_URL=http://localhost:9090 scripts/smoke-test.sh

BASE_URL="${BASE_URL:-http://localhost:8080}"
STAMP=$(date +%s)
USERNAME="smoketest${STAMP}"
EMAIL="smoketest${STAMP}@example.com"
PASSWORD="Password123!"

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1" >&2; exit 1; }

echo "Waiting for gateway routes to bind..."
ROUTES="[]"
for i in $(seq 1 24); do
    ROUTES=$(curl -s "$BASE_URL/actuator/gateway/routes" || echo "[]")
    [ "$ROUTES" != "[]" ] && [ -n "$ROUTES" ] && break
    sleep 5
done
[ "$ROUTES" != "[]" ] || fail "gateway routes never bound - is 'docker compose up --build' running?"
pass "gateway routes bound"

CODE=$(curl -sL -o /dev/null -w "%{http_code}" "$BASE_URL/swagger-ui.html")
[ "$CODE" = "200" ] || fail "swagger-ui.html returned $CODE, expected 200"
pass "swagger-ui.html -> 200"

for svc in user product cart order; do
    CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/${svc}-service/v3/api-docs")
    [ "$CODE" = "200" ] || fail "${svc}-service docs returned $CODE, expected 200"
done
pass "all 4 docs proxy routes -> 200"

REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
echo "$REGISTER_RESPONSE" | grep -q '"token"' || fail "register did not return a token: $REGISTER_RESPONSE"
pass "register -> token issued"

LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$TOKEN" ] || fail "login did not return a token: $LOGIN_RESPONSE"
pass "login -> token extracted"

ME_RESPONSE=$(curl -s "$BASE_URL/api/users/me" -H "Authorization: Bearer $TOKEN")
echo "$ME_RESPONSE" | grep -q "\"username\":\"$USERNAME\"" || fail "GET /api/users/me mismatch: $ME_RESPONSE"
pass "GET /api/users/me -> correct user"

echo
echo "All smoke checks passed."
