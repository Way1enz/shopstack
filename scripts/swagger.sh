#!/usr/bin/env bash
set -euo pipefail

# Confirms the aggregated Swagger UI and
# each service's proxied OpenAPI spec are reachable through the gateway, then proves
# the per-operation security split holds: GET /api/products works with no token,
# POST /api/products is rejected (401) without one. Assumes a stack is
# already running (docker compose up --build).
#
# Usage: scripts/swagger.sh

BASE_URL="${BASE_URL:-http://localhost:8080}"

pass() { echo "PASS: $1"; }
fail() {
  echo "FAIL: $1" >&2
  exit 1
}

echo "Waiting for gateway routes to bind..."
ROUTES="[]"
for i in $(seq 1 24); do
  ROUTES=$(curl -s "$BASE_URL/actuator/gateway/routes" || echo "[]")
  [ "$ROUTES" != "[]" ] && [ -n "$ROUTES" ] && break
  sleep 5
done
[ "$ROUTES" != "[]" ] || fail "gateway routes never bound - is 'docker compose up --build' running?"
pass "gateway routes bound"

echo
echo "=== Docs reachable through the gateway proxy ==="
for svc in user product cart order; do
  CODE=""
  for i in $(seq 1 12); do
    CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/${svc}-service/v3/api-docs")
    [ "$CODE" = "200" ] && break
    sleep 5
  done
  [ "$CODE" = "200" ] || fail "${svc}-service docs returned $CODE, expected 200"
done
pass "all 4 docs proxy routes -> 200"

UI_CODE=$(curl -sL -o /dev/null -w "%{http_code}" "$BASE_URL/swagger-ui.html")
[ "$UI_CODE" = "200" ] || fail "swagger-ui.html returned $UI_CODE, expected 200"
pass "swagger-ui.html -> 200"

echo
echo "=== Per-operation security matches what the docs claim ==="
GET_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/products")
[ "$GET_CODE" = "200" ] || fail "unauthenticated GET /api/products returned $GET_CODE, expected 200 (should be public)"
pass "unauthenticated GET /api/products -> 200 (public, as documented)"

POST_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/products" \
  -H "Content-Type: application/json" -d '{"name":"x","price":1,"stockQuantity":1,"category":"x"}')
[ "$POST_CODE" = "401" ] || fail "unauthenticated POST /api/products returned $POST_CODE, expected 401 (should be protected)"
pass "unauthenticated POST /api/products -> 401 (protected, as documented)"

echo
echo "All Swagger checks passed."
