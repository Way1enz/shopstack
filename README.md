# ShopStack

A backend microservices project simulating an e-commerce application.

## Starting services with Docker

```bash
docker compose up --build
```

Starts Postgres and Redis, then Eureka, then every service, then the gateway last.

- Discovery Server (Eureka) — <http://localhost:8761>
- API entry point (Gateway) — <http://localhost:8080>

```bash
docker compose down -v   # stop and wipe all data
```

For Postman (or Insomnia/Bruno), import `postman/ShopStack.postman_collection.json` and `postman/ShopStack-Local.postman_environment.json` — tokens get captured automatically after Register/Login.

## Services

- **user-service**: registration, login, JWT issuance, refresh tokens.
- **product-service**: product catalog, Redis as a cache in front of Postgres.
- **cart-service**: shopping cart, Redis as the only datastore.
- **order-service**: checkout, order history, publishes order events to Redis Streams.
- **notification-service**: consumes order events, no REST API of its own.
- **api-gateway**: single entry point, routing, JWT validation.
- **eureka-server**: service registry.

## Architecture

```
                              ┌──────────────────┐
                              │  eureka-server   │  :8761 (service registry)
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
                                       ▲        │ publishes (fire-and-forget)
                                       │        ▼
                   all client traffic  │  ┌───────────────────────────┐
                                       │  │  Redis Stream:            │
                                       │  │  "order-events"           │
                                       │  └─────────────────────────┬─┘
     ┌─────────────────┐               │                            │ consumer
     │   api-gateway   │───────────────┘                            │ group
     └────────▲────────┘ :8080 (single public entry point)          ▼
              │                                         ┌──────────────────────┐
              │                                         │ notification-service │
            client                                      │ :8085 (no REST API - │
(browser / Postman / frontend)                          │ background consumer) │
                                                        └──────────────────────┘
```

## Tech stack

- Java 17, Spring Boot 3.3.4, Spring Cloud 2023.0.3
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

*If your local JDK is newer than Java 17 you may hit Lombok errors (`cannot find symbol: method builder()`). Run Maven in a matching container instead:*
```bash
docker run --rm \
  -v "$(pwd)":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -w /workspace \
  maven:3.9-eclipse-temurin-17 \
  mvn test
```

## Path Finder

| Topic | Location |
|---|---|
| JWT validation | [`api-gateway/.../filter/AuthFilter.java`](api-gateway/src/main/java/com/ecommerce/gateway/filter/AuthFilter.java) |
| Password rules | [`user-service/.../validation/StrongPasswordValidator.java`](user-service/src/main/java/com/ecommerce/user/validation/StrongPasswordValidator.java) |
| Refresh token rotation | [`user-service/.../service/RefreshTokenService.java`](user-service/src/main/java/com/ecommerce/user/service/RefreshTokenService.java) |
| Payment validation (Luhn) | [`order-service/.../payment/PaymentService.java`](order-service/src/main/java/com/ecommerce/order/payment/PaymentService.java) |
| Checkout orchestration | [`order-service/.../service/OrderService.java`](order-service/src/main/java/com/ecommerce/order/service/OrderService.java) |
| Redis Streams consumer | [`notification-service/.../listener/OrderEventListener.java`](notification-service/src/main/java/com/ecommerce/notification/listener/OrderEventListener.java) |
| Gateway routes | [`api-gateway/.../resources/application.yml`](api-gateway/src/main/resources/application.yml) |
