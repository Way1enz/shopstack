#!/usr/bin/env bash
set -euo pipefail

# Scales product-service to N replicas and
# shows Spring Cloud LoadBalancer rotating across them via the X-Instance-Id response
# header. Assumes a stack is already running (docker compose up --build).
#
# Usage: scripts/load-balancing.sh
#        REPLICAS=5 scripts/load-balancing.sh

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPLICAS="${REPLICAS:-3}"

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

echo "Scaling product-service to $REPLICAS replicas..."
docker compose up --scale "product-service=$REPLICAS" --no-recreate -d

echo "Waiting for all $REPLICAS replicas to register with Eureka..."
EUREKA_URL="${EUREKA_URL:-http://localhost:8761}"
UP_COUNT=0
for i in $(seq 1 24); do
  UP_COUNT=$(curl -s -H "Accept: application/json" "$EUREKA_URL/eureka/apps/PRODUCT-SERVICE" \
    | grep -o '"status":"UP"' | wc -l | tr -d ' ')
  [ "$UP_COUNT" -ge "$REPLICAS" ] && break
  sleep 5
done
[ "$UP_COUNT" -ge "$REPLICAS" ] || {
  echo "Only $UP_COUNT/$REPLICAS replicas registered after 2 minutes - continuing anyway." >&2
}

# eureka.client.registry-fetch-interval-seconds and spring.cloud.loadbalancer.cache.ttl
# on api-gateway are both tuned to 5s (see application.yml), so this covers the gateway's
# own registry refresh plus its LoadBalancer instance-list cache.
echo "Waiting for the gateway's registry/loadbalancer cache to catch up..."
sleep 10

echo
echo "10 requests through the gateway, watching X-Instance-Id rotate:"
INSTANCE_IDS=""
for i in $(seq 1 10); do
  RESP=$(curl -s -D - "$BASE_URL/api/products" -o /dev/null)
  STATUS=$(echo "$RESP" | head -1 | tr -d '\r')
  INSTANCE=$(echo "$RESP" | grep -i X-Instance-Id | tr -d '\r' || echo "  (no X-Instance-Id header)")
  echo "$STATUS  $INSTANCE"
  ID=$(echo "$INSTANCE" | cut -d: -f2 | tr -d ' ')
  INSTANCE_IDS="$INSTANCE_IDS $ID"
done

UNIQUE_COUNT=$(echo "$INSTANCE_IDS" | tr ' ' '\n' | sed '/^$/d' | sort -u | wc -l | tr -d ' ')
echo
if [ "$UNIQUE_COUNT" -gt 1 ]; then
  echo "PASS: requests distributed across $UNIQUE_COUNT unique instances"
else
  echo "FAIL: all requests hit the same instance - load balancer is not rotating" >&2
fi

echo
echo "Scaling back down to 1 replica..."
docker compose up --scale product-service=1 --no-recreate -d
