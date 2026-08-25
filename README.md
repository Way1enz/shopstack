# ShopStack

[![CI](https://github.com/Way1enz/shopstack/actions/workflows/ci.yml/badge.svg)](https://github.com/Way1enz/shopstack/actions/workflows/ci.yml)

A backend microservices project simulating an e-commerce application.

## Starting services with Docker

If your local machine doesn't have Docker installed, follow [Docker's install guide](https://docs.docker.com/get-started/get-docker/)

Clone the repo, then from the project root:
```bash
docker compose up --build
```

Starts Postgres and Redis, then Eureka, then every service, then the gateway last.

- Discovery Server (Eureka): <http://localhost:8761>
- API entry point (Gateway): <http://localhost:8080>

On Colima (or any manually-configured Docker Engine), `buildx` may not be installed or wired up automatically. If `docker compose up --build` fails with a BuildKit error, install the plugin and link it manually:
```bash
brew install docker-buildx  # or use your package manager
mkdir -p ~/.docker/cli-plugins
ln -sfn "$(brew --prefix docker-buildx)/bin/docker-buildx" ~/.docker/cli-plugins/docker-buildx
docker buildx version
```

To shut it down, press Ctrl-C, or run one of the following commands in another terminal:
```bash
docker compose down      # stop, keep data
docker compose down -v   # stop and wipe all data
```

## Scaling

Any service can be scaled to multiple replicas. No fixed `container_name` entries exist in `docker-compose.yml`, so Compose handles naming automatically.

Start with multiple replicas from the beginning:
```bash
docker compose up --build --scale product-service=3
```

Scale up or down while already running:
```bash
docker compose up --scale product-service=5 --no-recreate -d
docker compose up --scale product-service=1 --no-recreate -d
```

## Services

- **user-service**: registration, login, JWT issuance, refresh tokens.
- **product-service**: product catalog, Redis as a cache in front of Postgres.
- **cart-service**: shopping cart, Redis as the only datastore.
- **order-service**: checkout, order history, publishes order events to Redis Streams.
- **notification-service**: consumes order events, no client-facing REST API. Its actuator port is exposed to the host for ops access.
- **api-gateway**: single entry point, routing, JWT validation.
- **eureka-server**: service registry.

## API Docs

Aggregated Swagger UI at the gateway: <http://localhost:8080/swagger-ui.html>. Dropdown switches between the four REST services; individual services aren't port-mapped to the host. Authorize with `Bearer <token>` from `/api/auth/login` to exercise protected endpoints. Requests go through the gateway, so auth and rate limiting apply the same as they would for any other client.

## Observability

- **Distributed tracing**: Zipkin + Micrometer Tracing (Brave) across every service. Zipkin UI: <http://localhost:9411>.
- **Async trace propagation**: HTTP/Feign hops get trace context for free. The Redis Streams hop (`order-service` to `notification-service`) doesn't, so context is manually injected on publish and extracted on consume; see `event/OrderEventPublisher.java` and `tracing/OrderEventTracing.java`. A redelivered message from crash recovery continues the same trace as a tagged child span rather than starting a disconnected one.
- **On-demand crash recovery**: `notification-service`'s pending-message recovery job runs on a schedule, or immediately via `POST http://localhost:8085/actuator/orderEventRecovery`.

## Resilience

- **order-service to cart-service/product-service (Feign)**: circuit breaker plus retry (Resilience4j) with connect/read timeouts, per client. An open circuit fails fast and skips retries. See `client/resilient/` and `application.yml`.
- **Gateway rate limiting**: Redis-backed token bucket per route, keyed by user id, falling back to IP for public routes. See `RateLimiterKeyResolver` and `application.yml`.

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
Every service also reports spans to Zipkin (<http://localhost:9411>).

## Tech stack

- Java 25 (virtual threads enabled), Spring Boot 4.1.1, Spring Cloud 2025.1.2
- Resilience4j (circuit breaker, retry) on order-service's Feign clients; Redis rate limiting on the gateway
- Micrometer Tracing plus Zipkin (Brave): distributed tracing across every service, including manual propagation over the Redis Streams hop
- Spring Data JPA + PostgreSQL, schema managed with Flyway
- Spring Data Redis (cache, primary datastore, and Streams)
- JJWT, with DB-backed rotating refresh tokens
- springdoc-openapi: Swagger UI aggregated at the gateway
- Maven multi-module reactor build
- Docker & Docker Compose

## Scripts

Everything under `scripts/` except `test.sh` assumes a stack is already running (`docker compose up --build`). Run order and flag details: [`scripts/README.md`](scripts/README.md).

**Setup**
- `test.sh`: runs the Maven reactor's tests (unit plus Testcontainers integration). Detects Colima and wires its socket automatically; falls back to the default Docker context (Docker Desktop, plain Docker CLI) otherwise.

**Functional checks**
- `smoke-test.sh`: quick black-box pass. Register, login, fetch profile.
- `full-functional.sh`: auth lifecycle, product CRUD, and a full cart to checkout to cancel round trip.
- `swagger.sh`: confirms the aggregated docs are reachable and the per-operation security matches what's documented.

**Resilience**
- `load-balancing.sh`: scales product-service and confirms requests rotate across instances.
- `idempotency.sh`: calls the same Idempotency-Key twice, confirms the second call doesn't re-apply.
- `rate-limiting.sh`: hammers the login route past its token bucket, watches 400s turn into 429s.
- `circuit-breaker.sh`: kills product-service mid-traffic, watches the circuit open then self-heal.
- `observability.sh`: traces a full checkout across every service; `--crash-recovery` also proves a redelivered message continues the same trace.

For more detail, or to adapt a script for your own testing, edit the scrips directly as each one is self-contained.

If your local JDK is newer than Java 25, `mvn`/`test.sh` may hit Lombok errors (`cannot find symbol: method builder()`). Run Maven in a matching container instead:
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
