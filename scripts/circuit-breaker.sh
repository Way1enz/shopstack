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
TOKEN=""
for i in $(seq 1 12); do
  TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/register" -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"Str0ngP@ss!\"}" |
    grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
  [ -n "$TOKEN" ] && break
  sleep 5
done
[ -n "$TOKEN" ] || {
  echo "register did not return a token" >&2
  exit 1
}

echo "Create a product..."
PRODUCT_ID=""
for i in $(seq 1 12); do
  PRODUCT_ID=$(curl -s -X POST "$BASE_URL/api/products" -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d '{"name":"Widget","price":9.99,"stockQuantity":50,"category":"test"}' |
    grep -o '"id":[0-9]*' | grep -o '[0-9]*' || true)
  [ -n "$PRODUCT_ID" ] && break
  sleep 5
done
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
echo "=== Restarting product-service, waiting for it to actually accept requests ==="
docker compose start product-service
echo "Container 'Started' means the process launched, not that Spring Boot/Eureka finished booting."
echo "Polling the real request path instead of a fixed sleep."
READY=""
for i in $(seq 1 24); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/products/$PRODUCT_ID")
  [ "$STATUS" = "200" ] && READY=1 && break
  sleep 5
done
[ -n "$READY" ] || fail "product-service never became reachable through the gateway again"

echo
echo "=== Hammering checkout again (expect 503 -> 201 once the circuit closes) ==="
echo "Only 3 of these 10 calls are the actual half-open trial (permitted-number-of-calls-in-half-open-state)."
echo "If a cold first connection among those 3 is slow enough to count as a failure, the breaker reopens"
echo "and offers another half-open window automatically 10s later (wait-duration-in-open-state)."
checkout_loop
if [ "$LAST_STATUS" != "201" ]; then
  echo "First half-open trial didn't close the circuit (status=$LAST_STATUS) - waiting for the next window..."
  sleep 10
  checkout_loop
fi
if [ "$LAST_STATUS" = "201" ]; then
  pass "circuit closed, request 10 succeeded with 201"
else
  fail "request 10 returned $LAST_STATUS, expected 201 (circuit should be closed by request 10 of 10)"
fi
