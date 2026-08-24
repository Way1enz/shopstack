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
  for i in $(seq 1 10); do
    curl -s -o /dev/null -w "[$i] status=%{http_code}  time=%{time_total}s\n" \
      -X POST "$BASE_URL/api/orders/checkout" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"paymentMethod":"CASH"}'
    # checkout clears the cart on success or failure either way, so it needs refilling every loop
    add_to_cart
  done
}

add_to_cart

echo
echo "=== Stopping product-service, hammering checkout (expect 502 -> 503 -> fast 503s) ==="
docker compose stop product-service
checkout_loop

echo
echo "=== Restarting product-service, waiting ~25s for boot + Eureka registration ==="
docker compose start product-service
sleep 25

echo
echo "=== Hammering checkout again (expect 503 -> 201 once the circuit closes) ==="
checkout_loop
