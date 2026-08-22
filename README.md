# ShopStack

[![CI](https://github.com/Way1enz/shopstack/actions/workflows/ci.yml/badge.svg)](https://github.com/Way1enz/shopstack/actions/workflows/ci.yml)

A backend microservices project simulating an e-commerce application.

## Starting services with Docker

```bash
docker compose up --build
```

Starts Postgres and Redis, then Eureka, then every service, then the gateway last.

- Discovery Server (Eureka) — <http://localhost:8761>
- API entry point (Gateway) — <http://localhost:8080>

```bash
docker compose down      # stop, keep data
docker compose down -v   # stop and wipe all data
```

## Scaling and load balancing

Any service can be scaled to multiple replicas — no fixed `container_name` entries exist in `docker-compose.yml`, so Compose handles naming automatically.

Start with multiple replicas (replace the normal startup command):
```bash
docker compose up --build --scale product-service=3
```

Scale up/down while already running — no restart needed:
```bash
docker compose up --scale product-service=5 --no-recreate -d
docker compose up --scale product-service=1 --no-recreate -d
```

Every response from `product-service` includes an `X-Instance-Id` header containing the container's hostname, so you can observe Spring Cloud LoadBalancer distributing requests across instances:
```bash
for i in {1..10}; do curl -s -D - http://localhost:8080/api/products -o /dev/null | grep X-Instance-Id; done
```
With 3 replicas registered in Eureka, you should see the header value rotate across 3 different container IDs. Verify all replicas registered at <http://localhost:8761> under `PRODUCT-SERVICE` before running the loop — allow ~30 seconds after startup for registration.

## Services

- **user-service**: registration, login, JWT issuance, refresh tokens.
- **product-service**: product catalog, Redis as a cache in front of Postgres.
- **cart-service**: shopping cart, Redis as the only datastore.
- **order-service**: checkout, order history, publishes order events to Redis Streams.
- **notification-service**: consumes order events, no client-facing REST API — its actuator port is exposed to the host for ops access.
- **api-gateway**: single entry point, routing, JWT validation.
- **eureka-server**: service registry.

## Observability

- **Distributed tracing**: Zipkin + Micrometer Tracing (Brave) across every service. Zipkin UI — <http://localhost:9411>.
- **Async trace propagation**: HTTP/Feign hops get trace context for free; the Redis Streams hop (`order-service` → `notification-service`) doesn't, so context is manually injected on publish and extracted on consume — see `event/OrderEventPublisher.java` and `tracing/OrderEventTracing.java`. A redelivered message (crash recovery) continues the *same* trace as a tagged child span rather than starting a disconnected one.
- **On-demand crash recovery**: `notification-service`'s pending-message recovery job runs on a schedule, or immediately via `POST http://localhost:8085/actuator/orderEventRecovery`.

## Resilience

- **order-service → cart-service/product-service (Feign)**: circuit breaker + retry (Resilience4j) + connect/read timeouts, per client. Retries skip and an open circuit. See `client/resilient/` and `application.yml`.
- **Gateway rate limiting**: Redis-backed token bucket per route, keyed by user id (falls back to IP for public routes). See `RateLimiterKeyResolver` and `application.yml`.

## Architecture

```
                              ┌──────────────────┐
                              │  eureka-server   │ :8761 (service registry)
                              └────────▲─────────┘
                                       │ registers with
        ┌──────────────────────────────┼───────────────────────────────┐
        │                              │                               │
┌───────▼──────┐               ┌───────▼───────┐               ┌───────▼──────┐
│ user-service │               │product-service│               │ cart-service │
│   :8081      │               │    :8082      │               │   :8083      │
│  (Postgres)  │               │ (Postgres+    │               │   (Redis)    │
│              │               │  Redis cache) │               │              │
└──────────────┘               └────────▲──────┘               │              │
                                        │                      └──────▲───────┘
                                        │ Feign                       │ Feign
                                ┌───────┴─────────────────────────────┘
                                │     order-service :8084 (Postgres)  │
                                └───────────────┬─────────────────────┘
                                     ▲          │ publishes (fire-and-forget)
                                     │          ▼
                 all client traffic  │  ┌───────────────────────────┐
                                     │  │  Redis Stream:            │
                                     │  │  "order-events"           │
                                     │  └─────────────────────────┬─┘
   ┌─────────────────┐               │                            │ consumer
   │   api-gateway   │───────────────┘                            │ group
   └────────▲────────┘ :8080 (single public entry point)          ▼
            │                                          ┌──────────────────────┐
            │                                          │ notification-service │
          client                                       │ :8085 (no client API │
    (browser / frontend)                               │ - actuator exposed)  │
                                                       └──────────────────────┘
```
Every service also reports spans to Zipkin (<http://localhost:9411>) — omitted above to keep the request-flow diagram readable; see the Observability section.

## Tech stack

- Java 25 (virtual threads enabled), Spring Boot 4.1.0, Spring Cloud 2025.1.2
- Resilience4j (circuit breaker, retry) on order-service's Feign clients; Redis rate limiting on the gateway
- Micrometer Tracing + Zipkin (Brave) — distributed tracing across every service, including manual propagation over the Redis Streams hop
- Spring Data JPA + PostgreSQL, schema managed with Flyway
- Spring Data Redis (cache, primary datastore, and Streams)
- JJWT, with DB-backed rotating refresh tokens
- Maven multi-module reactor build
- Docker & Docker Compose

## Tests

```bash
mvn test
```

Unit tests run with plain Mockito. Integration tests spin up Postgres/Redis via Testcontainers.

*If your local JDK is newer than Java 25 you may hit Lombok errors (`cannot find symbol: method builder()`). Run Maven in a matching container instead:*
```bash
docker run --rm \
  -v "$(pwd)":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -w /workspace \
  maven:3.9-eclipse-temurin-25 \
  mvn test
```

## Path Finder

| Topic | Location |
|---|---|
| JWT validation | [`api-gateway/.../filter/AuthFilter.java`](api-gateway/src/main/java/com/ecommerce/gateway/filter/AuthFilter.java) |
| Refresh token rotation | [`user-service/.../service/RefreshTokenService.java`](user-service/src/main/java/com/ecommerce/user/service/RefreshTokenService.java) |
| Checkout orchestration | [`order-service/.../service/OrderService.java`](order-service/src/main/java/com/ecommerce/order/service/OrderService.java) |
| Gateway routes | [`api-gateway/.../resources/application.yml`](api-gateway/src/main/resources/application.yml) |
| Load balancing (instance header) | [`product-service/.../config/InstanceIdFilter.java`](product-service/src/main/java/com/ecommerce/product/config/InstanceIdFilter.java) |
| Circuit breaker/retry wrappers | [`order-service/.../client/resilient/`](order-service/src/main/java/com/ecommerce/order/client/resilient) |
| Gateway rate limit key resolution | [`api-gateway/.../filter/RateLimiterKeyResolver.java`](api-gateway/src/main/java/com/ecommerce/gateway/filter/RateLimiterKeyResolver.java) |



