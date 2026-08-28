#!/usr/bin/env bash
set -euo pipefail

# The full functional pass beyond the
# smoke test: auth register/login/refresh/logout, product CRUD, and a full
# cart -> checkout -> stock-decrement -> cancel -> stock-restore round trip
# through Part 5's Feign chain. Assumes a stack is already running
# (docker compose up --build).
#
# Usage: scripts/full-functional.sh

BASE_URL="${BASE_URL:-http://localhost:8080}"
STAMP=$(date +%s)
USERNAME="fulltest${STAMP}"
EMAIL="fulltest${STAMP}@example.com"
PASSWORD="Password123!"

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
echo "=== Auth: register -> login -> refresh -> logout ==="

REGISTER_RESPONSE=""
for i in $(seq 1 12); do
  REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/register" -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
  echo "$REGISTER_RESPONSE" | grep -q '"token"' && break
  sleep 5
done
echo "$REGISTER_RESPONSE" | grep -q '"token"' || fail "register did not return a token: $REGISTER_RESPONSE"
pass "register -> token issued"

LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
REFRESH_TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"refreshToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$TOKEN" ] && [ -n "$REFRESH_TOKEN" ] || fail "login did not return token+refreshToken: $LOGIN_RESPONSE"
pass "login -> token extracted"

REFRESH_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/refresh" -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}")
NEW_REFRESH_TOKEN=$(echo "$REFRESH_RESPONSE" | grep -o '"refreshToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$NEW_REFRESH_TOKEN" ] || fail "refresh did not return a new refreshToken: $REFRESH_RESPONSE"
pass "refresh -> rotated refreshToken issued"

LOGOUT_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/auth/logout" \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$NEW_REFRESH_TOKEN\"}")
[ "$LOGOUT_CODE" = "204" ] || fail "logout returned $LOGOUT_CODE, expected 204"
pass "logout -> 204"

echo
echo "=== Products: GET (public) -> POST/PUT/DELETE (protected) ==="

LIST_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/products")
[ "$LIST_CODE" = "200" ] || fail "GET /api/products returned $LIST_CODE, expected 200"
pass "GET /api/products (no auth needed) -> 200"

CRUD_RESPONSE=$(curl -s -X POST "$BASE_URL/api/products" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"CRUD Widget","description":"full-pass test","price":5.00,"stockQuantity":10,"category":"test"}')
CRUD_PRODUCT_ID=$(echo "$CRUD_RESPONSE" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
[ -n "$CRUD_PRODUCT_ID" ] || fail "product creation did not return an id: $CRUD_RESPONSE"
pass "POST /api/products -> id $CRUD_PRODUCT_ID"

UPDATE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/api/products/$CRUD_PRODUCT_ID" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"CRUD Widget Updated","description":"full-pass test","price":6.00,"stockQuantity":10,"category":"test"}')
[ "$UPDATE_CODE" = "200" ] || fail "PUT /api/products/$CRUD_PRODUCT_ID returned $UPDATE_CODE, expected 200"
pass "PUT /api/products/$CRUD_PRODUCT_ID -> 200"

DELETE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/api/products/$CRUD_PRODUCT_ID" \
  -H "Authorization: Bearer $TOKEN")
[ "$DELETE_CODE" = "204" ] || fail "DELETE /api/products/$CRUD_PRODUCT_ID returned $DELETE_CODE, expected 204"
pass "DELETE /api/products/$CRUD_PRODUCT_ID -> 204"

echo
echo "=== Cart -> Checkout -> stock decrement -> cancel -> stock restore ==="

STOCK_RESPONSE=$(curl -s -X POST "$BASE_URL/api/products" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Stock Widget","description":"checkout test","price":8.00,"stockQuantity":20,"category":"test"}')
STOCK_PRODUCT_ID=$(echo "$STOCK_RESPONSE" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
ORIGINAL_STOCK=$(echo "$STOCK_RESPONSE" | grep -o '"stockQuantity":[0-9]*' | head -1 | grep -o '[0-9]*$')
[ -n "$STOCK_PRODUCT_ID" ] && [ -n "$ORIGINAL_STOCK" ] || fail "product creation did not return id+stockQuantity: $STOCK_RESPONSE"
pass "created product $STOCK_PRODUCT_ID with stockQuantity=$ORIGINAL_STOCK"

QUANTITY=2
curl -s -X POST "$BASE_URL/api/cart/items" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d "{\"productId\":$STOCK_PRODUCT_ID,\"quantity\":$QUANTITY}" >/dev/null
pass "added $QUANTITY to cart"

CHECKOUT_RESPONSE=$(curl -s -X POST "$BASE_URL/api/orders/checkout" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"shippingAddress":"123 Test St","paymentMethod":"CASH"}')
ORDER_ID=$(echo "$CHECKOUT_RESPONSE" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
[ -n "$ORDER_ID" ] || fail "checkout did not return an order id: $CHECKOUT_RESPONSE"
pass "checkout -> order $ORDER_ID"

AFTER_CHECKOUT_STOCK=$(curl -s "$BASE_URL/api/products/$STOCK_PRODUCT_ID" |
  grep -o '"stockQuantity":[0-9]*' | head -1 | grep -o '[0-9]*$')
EXPECTED_AFTER_CHECKOUT=$((ORIGINAL_STOCK - QUANTITY))
[ "$AFTER_CHECKOUT_STOCK" = "$EXPECTED_AFTER_CHECKOUT" ] ||
  fail "stock after checkout is $AFTER_CHECKOUT_STOCK, expected $EXPECTED_AFTER_CHECKOUT"
pass "stock decremented: $ORIGINAL_STOCK -> $AFTER_CHECKOUT_STOCK"

curl -s -X POST "$BASE_URL/api/orders/$ORDER_ID/cancel" -H "Authorization: Bearer $TOKEN" >/dev/null
pass "cancelled order $ORDER_ID"

AFTER_CANCEL_STOCK=$(curl -s "$BASE_URL/api/products/$STOCK_PRODUCT_ID" |
  grep -o '"stockQuantity":[0-9]*' | head -1 | grep -o '[0-9]*$')
[ "$AFTER_CANCEL_STOCK" = "$ORIGINAL_STOCK" ] ||
  fail "stock after cancel is $AFTER_CANCEL_STOCK, expected original $ORIGINAL_STOCK"
pass "stock restored: $AFTER_CHECKOUT_STOCK -> $AFTER_CANCEL_STOCK"

echo
echo "All functional checks passed."
