#!/usr/bin/env bash
set -euo pipefail

# Hammers /api/auth/login with a bogus
# body, since the request itself is what's rate-limited, not whether it's valid, until
# the gateway's RequestRateLimiter bucket (replenishRate 5, burstCapacity 10) empties
# and starts returning 429 instead of 400. Assumes a stack is already running
# (docker compose up --build).
#
# Usage: scripts/rate-limiting.sh

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
[ "$ROUTES" != "[]" ] || {
  echo "gateway routes never bound - is 'docker compose up --build' running?" >&2
  exit 1
}

# Bucket state persists in Redis between runs (keyed by IP), so the exact cutoff
# point shifts if you rerun this before the bucket has had ~2s to refill. Printing
# the remaining-token header makes that visible instead of the count looking random.
echo "15 requests, expect ~10x 400 (bad body, bucket not empty yet) then 429 (bucket empty):"
echo "Every request consumes a token regardless of body validity."
COUNT_429=0
for i in $(seq 1 15); do
  RESP=$(curl -s -D - -o /dev/null -w "HTTPSTATUS:%{http_code}" -X POST "$BASE_URL/api/auth/login" \
    -H "Content-Type: application/json" -d '{}')
  STATUS=$(echo "$RESP" | grep -o 'HTTPSTATUS:[0-9]*' | cut -d: -f2)
  REMAINING=$(echo "$RESP" | grep -i 'X-RateLimit-Remaining' | tr -d '\r' | cut -d: -f2 | tr -d ' ' || true)
  printf "[%d] %s  remaining=%s\n" "$i" "$STATUS" "${REMAINING:-?}"
  [ "$STATUS" = "429" ] && COUNT_429=$((COUNT_429 + 1))
done

echo
if [ "$COUNT_429" -ge 1 ]; then
  pass "rate limiter tripped, $COUNT_429/15 requests got 429"
else
  fail "never hit 429 - rate limiter did not trip"
fi
