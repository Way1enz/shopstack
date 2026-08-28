#!/usr/bin/env bash
set -euo pipefail

# Calls decrement-stock twice with the
# same Idempotency-Key directly against product-service (bypassing the gateway,
# same as the real order-service -> product-service Feign call does) and confirms
# the second call didn't re-apply the decrement. Assumes a stack is already running
# (docker compose up --build).
#
# Usage: scripts/idempotency.sh

BASE_URL="${BASE_URL:-http://localhost:8080}"
NETWORK="${NETWORK:-ecommerce-microservices_ecommerce-net}"
IDEMPOTENCY_KEY="dedup-test-$(date +%s)"
STAMP=$(date +%s)
USERNAME="idemtest${STAMP}"
EMAIL="idemtest${STAMP}@example.com"

echo "Waiting for gateway routes to bind..."
ROUTES="[]"
for i in $(seq 1 24); do
  ROUTES=$(curl -s "$BASE_URL/actuator/gateway/routes" || echo "[]")
  [ "$ROUTES" != "[]" ] && [ -n "$ROUTES" ] && break
  sleep 5
done
[ "$ROUTES" != "[]" ] || {
  echo "gateway routes never bound - is 'docker compose up --build' running?" >&2
  exit 1
}

REGISTER_RESP=""
for i in $(seq 1 12); do
  REGISTER_RESP=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/auth/register" -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"Password123!\"}")
  REGISTER_STATUS=$(echo "$REGISTER_RESP" | tail -1)
  if [ "$REGISTER_STATUS" = "200" ] || [ "$REGISTER_STATUS" = "201" ]; then
    break
  fi
  sleep 5
done
REGISTER_STATUS=$(echo "$REGISTER_RESP" | tail -1)
REGISTER_BODY=$(echo "$REGISTER_RESP" | sed '$d')
TOKEN=$(echo "$REGISTER_BODY" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
[ -n "$TOKEN" ] || {
  echo "register did not return a token (HTTP $REGISTER_STATUS): $REGISTER_BODY" >&2
  exit 1
}

CREATE_RESP=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/products" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"name":"Idempotency Widget","price":4.00,"stockQuantity":30,"category":"test"}')
CREATE_STATUS=$(echo "$CREATE_RESP" | tail -1)
CREATE_BODY=$(echo "$CREATE_RESP" | sed '$d')
PRODUCT_ID=$(echo "$CREATE_BODY" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*' || true)
[ -n "$PRODUCT_ID" ] || {
  echo "product creation did not return an id (HTTP $CREATE_STATUS): $CREATE_BODY" >&2
  exit 1
}
echo "Product $PRODUCT_ID created with stockQuantity=30"

echo
echo "First call - real decrement, key not seen before:"
RESULT_1=$(docker run --rm --network "$NETWORK" curlimages/curl -sS -X POST \
  "http://product-service:8082/api/products/$PRODUCT_ID/decrement-stock?quantity=1" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" | grep -o '"stockQuantity":[0-9]*' | cut -d: -f2)
echo "stockQuantity: $RESULT_1"

echo
echo "Second call, same key - should be recognized as a duplicate:"
RESULT_2=$(docker run --rm --network "$NETWORK" curlimages/curl -sS -X POST \
  "http://product-service:8082/api/products/$PRODUCT_ID/decrement-stock?quantity=1" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" | grep -o '"stockQuantity":[0-9]*' | cut -d: -f2)
echo "stockQuantity: $RESULT_2"

echo
if [ "$RESULT_1" = "$RESULT_2" ]; then
  echo "PASS: stockQuantity identical both times ($RESULT_1) - second call did not re-decrement"
else
  echo "FAIL: stockQuantity differs ($RESULT_1 vs $RESULT_2) - idempotency key was not honored" >&2
  exit 1
fi
