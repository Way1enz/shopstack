#!/usr/bin/env bash
set -euo pipefail

# Stops product-service, hammers checkout
# to show Resilience4j's retry -> circuit-open -> instant-rejection progression, then
# restarts product-service and shows the circuit self-heal. Assumes a stack is
# already running (docker compose up --build). Stops/restarts product-service as
# part of the demo, so don't run against anything you need to stay up.
#
# Usage: scripts/circuit-breaker.sh

BASE_URL="${BASE_URL:-http://localhost:8080}"
STAMP=$(date +%s)
USERNAME="cbdemo${STAMP}"
EMAIL="cbdemo${STAMP}@example.com"

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
[ "$ROUTES" != "[]" ] || {
  echo "gateway routes never bound - is 'docker compose up --build' running?" >&2
  exit 1
}

echo "Register (token comes straight off the register response, no separate login)..."
TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"Str0ngP@ss!\"}" |
  grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$TOKEN" ] || {
  echo "register did not return a token" >&2
  exit 1
}

echo "Create a product..."
PRODUCT_ID=$(curl -s -X POST "$BASE_URL/api/products" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"name":"Widget","price":9.99,"stockQuantity":50,"category":"test"}' |
  grep -o '"id":[0-9]*' | grep -o '[0-9]*')
[ -n "$PRODUCT_ID" ] || {
  echo "product creation did not return an id" >&2
  exit 1
}

add_to_cart() {
  curl -s -X POST "$BASE_URL/api/cart/items" -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d "{\"productId\":$PRODUCT_ID,\"quantity\":1}" >/dev/null
}

checkout_loop() {
  LAST_STATUS=""
  for i in $(seq 1 10); do
    RESP=$(curl -s -o /dev/null -w "%{http_code} %{time_total}" \
      -X POST "$BASE_URL/api/orders/checkout" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"paymentMethod":"CASH"}')
    LAST_STATUS=$(echo "$RESP" | cut -d' ' -f1)
    LAST_TIME=$(echo "$RESP" | cut -d' ' -f2)
    echo "[$i] status=$LAST_STATUS  time=${LAST_TIME}s"
    # checkout clears the cart on success or failure either way, so it needs refilling every loop
    add_to_cart
  done
}

add_to_cart

echo
echo "=== Stopping product-service, hammering checkout (expect 502 -> 503 -> fast 503s) ==="
echo "Sliding window 10, opens once >=5 calls seen and >=50% fail (order-service application.yml)."
echo "Early calls still attempt a real connection. Once the breaker opens, later calls skip the network entirely."
echo "Request 1's ~15-20s delay is the OS TCP connect timeout against the stopped container."
docker compose stop product-service
checkout_loop
if [ "$LAST_STATUS" = "503" ]; then
  pass "circuit open, request 10 fast-failed with 503"
else
  fail "request 10 returned $LAST_STATUS, expected 503 (circuit should be open by request 10 of 10)"
fi

echo
echo "=== Restarting product-service, waiting ~25s for boot + Eureka registration ==="
docker compose start product-service
sleep 25

echo
echo "=== Hammering checkout again (expect 503 -> 201 once the circuit closes) ==="
echo "wait-duration-in-open-state is 10s. The 25s wait above already put it in HALF_OPEN."
echo "These 10 calls are the half-open test batch. Success here closes the circuit."
checkout_loop
if [ "$LAST_STATUS" = "201" ]; then
  pass "circuit closed, request 10 succeeded with 201"
else
  fail "request 10 returned $LAST_STATUS, expected 201 (circuit should be closed by request 10 of 10)"
fi
