#!/usr/bin/env bash
set -euo pipefail

# Runs a full checkout through the gateway,
# pulls the trace ID off the order-events Redis Stream record, and queries Zipkin to
# confirm every service on the checkout path, including notification-service (which
# never receives an HTTP request), shares one trace. Assumes a stack is already
# running (docker compose up --build).
#
# Usage: scripts/observability.sh                   # happy path only
#        scripts/observability.sh --crash-recovery  # also stops
#          notification-service, forces a message into a delivered-but-unacked
#          state, and confirms the redelivery continues the same trace as a
#          child span

CRASH_RECOVERY=false
for arg in "$@"; do
  case "$arg" in
  --crash-recovery) CRASH_RECOVERY=true ;;
  *)
    echo "Unknown flag: $arg (expected --crash-recovery)" >&2
    exit 1
    ;;
  esac
done

BASE_URL="${BASE_URL:-http://localhost:8080}"
ZIPKIN_URL="${ZIPKIN_URL:-http://localhost:9411}"

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

run_checkout() {
  local stamp user email token product_id
  stamp=$(date +%s%N)
  user="traceuser${stamp}"
  email="traceuser${stamp}@test.com"

  token=$(curl -s -X POST "$BASE_URL/api/auth/register" -H "Content-Type: application/json" \
    -d "{\"username\":\"$user\",\"email\":\"$email\",\"password\":\"Trace123!@#\"}" |
    grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
  [ -n "$token" ] || {
    echo "register did not return a token" >&2
    exit 1
  }

  product_id=$(curl -s -X POST "$BASE_URL/api/products" \
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    -d '{"name":"Trace Widget","description":"observability test","price":19.99,"stockQuantity":50,"category":"test"}' |
    grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*' || true)
  [ -n "$product_id" ] || {
    echo "product creation did not return an id" >&2
    exit 1
  }

  curl -s -X POST "$BASE_URL/api/cart/items" \
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    -d "{\"productId\":$product_id,\"quantity\":2}" >/dev/null

  curl -s -X POST "$BASE_URL/api/orders/checkout" \
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    -d '{"shippingAddress":"123 Test St","paymentMethod":"CASH"}' >/dev/null
}

echo
echo "=== Happy path: full checkout through the gateway ==="
run_checkout

TRACE_ID=$(docker compose exec -T redis redis-cli XREVRANGE order-events + - COUNT 1 |
  grep -A1 traceparent | tail -1 | tr -d ' "' | cut -d'-' -f2 || true)
[ -n "$TRACE_ID" ] || {
  echo "could not read a traceparent off the order-events stream" >&2
  exit 1
}

curl -s "$ZIPKIN_URL/api/v2/trace/$TRACE_ID" -o /tmp/trace.json
# Zipkin's span export is async and different services flush on different timing, so
# api-gateway's spans can land well before order-service/cart-service/product-service's,
# and notification-service's consumer span (async, off the Redis Stream) lands last of all.
# Poll until both order-service and notification-service specifically show up; a generic
# non-empty check would pass too early on api-gateway's span alone.
for i in $(seq 1 10); do
  grep -q '"serviceName":"order-service"' /tmp/trace.json 2>/dev/null &&
    grep -q '"serviceName":"notification-service"' /tmp/trace.json 2>/dev/null && break
  sleep 2
  curl -s "$ZIPKIN_URL/api/v2/trace/$TRACE_ID" -o /tmp/trace.json
done
echo "Trace ID: $TRACE_ID"
SERVICES=$(grep -o '"serviceName":"[^"]*"' /tmp/trace.json | sort -u)
echo "Services in this trace:"
echo "$SERVICES"
echo "('redis' above is Lettuce's own per-command tracing, separate from the consumer span below)"
# The checkout path is api-gateway -> order-service -> (cart-service, product-service via
# Feign) -> notification-service (async, via Redis Streams, no HTTP call). No user-service
# call here since order-service doesn't hold a UserClient.
MISSING=""
for svc in api-gateway order-service cart-service product-service notification-service; do
  echo "$SERVICES" | grep -q "\"$svc\"" || MISSING="$MISSING $svc"
done
if [ -z "$MISSING" ]; then
  pass "all 5 expected services present in one trace, including notification-service (async only, no HTTP call)"
else
  fail "missing from trace:$MISSING"
fi

echo
echo "Async Redis Streams hop: notification-service consumes this event from the stream."
echo "order-service never makes an HTTP call to notification-service."
if grep -q '"name":"order-events receive"' /tmp/trace.json; then
  pass "order-events receive span found (async consumer traced)"
else
  fail "no async consumer span found for order-events receive"
fi

if [ "$CRASH_RECOVERY" = true ]; then
  echo
  echo "=== Crash recovery: force a delivered-but-unacked message, confirm the redelivery continues the same trace ==="
  docker compose stop notification-service

  run_checkout

  RECOVERY_TRACE_ID=$(docker compose exec -T redis redis-cli XREVRANGE order-events + - COUNT 1 |
    grep -A1 traceparent | tail -1 | tr -d ' "' | cut -d'-' -f2 || true)
  [ -n "$RECOVERY_TRACE_ID" ] || {
    echo "could not read a traceparent off the order-events stream" >&2
    exit 1
  }

  # claim the message into the Pending Entries List without acking it. This is what
  # "delivered but the consumer crashed before processing" looks like in Redis
  echo "Claiming the message (simulates notification-service crashing mid-process):"
  docker compose exec -T redis redis-cli XREADGROUP GROUP notification-service-group notification-consumer-1 COUNT 1 STREAMS order-events '>'
  echo
  echo "Pending Entries List - message delivered but never acknowledged:"
  echo "(summary form: total pending, smallest ID, greatest ID, then per-consumer pending count)"
  docker compose exec -T redis redis-cli XPENDING order-events notification-service-group

  docker compose start notification-service
  echo "Waiting for notification-service to actually be ready (container 'Up' != app booted)..."
  for i in $(seq 1 24); do
    curl -s -o /dev/null http://localhost:8085/actuator/health && break
    sleep 5
  done

  echo "Triggering the recovery scan on demand..."
  curl -s -X POST http://localhost:8085/actuator/orderEventRecovery -H "Content-Type: application/json" || {
    echo "recovery endpoint unreachable - notification-service may still be starting" >&2
    exit 1
  }
  echo

  curl -s "$ZIPKIN_URL/api/v2/trace/$RECOVERY_TRACE_ID" -o /tmp/trace2.json
  # The redelivered span only exists after the recovery scan actually runs (just above),
  # so it exports later than the original checkout spans. Poll for that specific tag;
  # the original spans alone would already satisfy a plain non-empty check.
  for i in $(seq 1 10); do
    grep -q '"messaging.redelivered":"true"' /tmp/trace2.json 2>/dev/null && break
    sleep 2
    curl -s "$ZIPKIN_URL/api/v2/trace/$RECOVERY_TRACE_ID" -o /tmp/trace2.json
  done
  REDELIVERED=$(grep -o '"messaging.redelivered":"[^"]*"' /tmp/trace2.json | cut -d'"' -f4)
  if [ "$REDELIVERED" = "true" ]; then
    pass "redelivered message continues the original trace as a child span (messaging.redelivered=true)"
  else
    fail "messaging.redelivered was '${REDELIVERED:-not found}', expected true"
  fi
fi
