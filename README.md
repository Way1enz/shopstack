# ShopStack — E-Commerce Microservices

A small e-commerce backend built to get practice with microservices: Spring Boot, Spring Cloud (Eureka, Gateway, OpenFeign), Redis, Postgres, and Docker.

---

## 1. Architecture

```
                              ┌──────────────────┐
                              │  eureka-server   │  (service registry, :8761)
                              └────────▲─────────┘
                                       │ registers with
        ┌──────────────────────────────┼───────────────────────────────┐
        │                              │                               │
┌───────▼──────┐               ┌───────▼───────┐               ┌───────▼──────┐
│ user-service │               │product-service│               │ cart-service │
│   :8081      │               │    :8082      │               │   :8083      │
│  (Postgres)  │               │ (Postgres+    │               │   (Redis     │
│              │               │  Redis cache) │               │  ONLY - no   │
└──────────────┘               └──────▲────────┘               │  Postgres)   │
                                      │                        └──────▲───────┘
                                      │ Feign                         │ Feign
                                ┌─────┴───────────────────────────────┘
                                │    order-service :8084 (Postgres)   │
                                └───────────────┬─────────────────────┘
                                       ▲        │ publishes (fire-and-forget)
                                       │        ▼
                                       │  ┌───────────────────────────┐
                                       │  │  Redis Stream:            │
                                       │  │  "order-events"           │
                                       │  └────────────┬──────────────┘
                                       │               │ consumer group
                                       │               ▼
                                       │  ┌───────────────────────────┐
                                       │  │  notification-service     │
                                       │  │  :8085 (no REST API -     │
                                       │  │  background consumer)     │
                                       │  └───────────────────────────┘
                                       │ all client traffic
                              ┌────────┴──────────┐
                              │   api-gateway     │  :8080  (single public entry point)
                              └────────▲──────────┘
                                       │
                                    client
                          (browser / Postman / frontend)
```

| Service               | Port | Data store            | Responsibility                                          |
|------------------------|------|------------------------|----------------------------------------------------------|
| `eureka-server`        | 8761 | —                      | Service registry / discovery                             |
| `api-gateway`          | 8080 | —                      | Single entry point, routing, JWT validation               |
| `user-service`         | 8081 | Postgres (`user_db`)   | Registration, login, JWT issuance, refresh tokens          |
| `product-service`      | 8082 | Postgres + Redis       | Product catalog, Redis as a cache                          |
| `cart-service`         | 8083 | Redis only             | Shopping cart, Redis as the primary datastore              |
| `order-service`        | 8084 | Postgres (`order_db`)  | Checkout orchestration, order history, publishes order events |
| `notification-service` | 8085 | —                      | Background consumer of order events, Redis as a message broker |

Clients only talk to `api-gateway` on port `8080`. Everything else stays inside the Docker network (though the ports are exposed on localhost too, just for convenience while developing). `notification-service` isn't reachable from outside at all — it has no REST endpoints, nothing calls it, it only consumes from the stream.

Redis ends up doing three different jobs here on purpose, since I wanted to actually use it in more than one way rather than just bolt it on as a cache: `product-service` uses it as a cache in front of Postgres (`@Cacheable`/`@CacheEvict`, TTL-based, can be wiped and rebuilt at any time). `cart-service` uses it as the actual database — carts are short-lived and don't need relational querying, so there's no Postgres fallback for them at all. And `order-service`/`notification-service` use Redis Streams as a lightweight message broker for the async notification flow (more on that below).

See [PROJECT_GUIDE.md](./PROJECT_GUIDE.md) for a file-by-file breakdown of every module.

---

## 2. Tech stack

- Java 17
- Spring Boot 3.3.4
- Spring Cloud 2023.0.3 (Netflix Eureka, Gateway, OpenFeign)
- Spring Data JPA + PostgreSQL
- Spring Data Redis (cache, primary datastore, and Streams)
- JJWT for stateless auth, with DB-backed rotating refresh tokens
- Maven (multi-module reactor build)
- Docker & Docker Compose

---

## 3. Prerequisites

- Docker & Docker Compose (easiest way to run everything)
- OR, to run locally without Docker: JDK 17, Maven 3.9+, a local Postgres instance, and a local Redis instance.

---

## 4. Running with Docker (recommended)

**Case where Postgres or Redis is running locally**: there's no need to stop them or set anything up — the containers below are self-contained and use their own volumes. One thing worth knowing: Postgres is exposed to your host on port `5433`, not the default `5432`, so it won't collide with a local Postgres install. Services still talk to each other on the container's normal port `5432` internally — the remap only changes how you'd connect from your host machine (e.g. `psql -h localhost -p 5433`).

From the project root:

```bash
docker compose up --build
```

This will:
1. Start `postgres` (and create `user_db`, `product_db`, `order_db` via the init script in `docker/postgres-init/`) and `redis`.
2. Start `eureka-server` and wait for it to become healthy.
3. Build and start `user-service`, `product-service`, `cart-service`, `order-service`, `notification-service`.
4. Build and start `api-gateway` last.

First build takes a few minutes (Maven has to pull dependencies inside the build containers). Later builds are much faster thanks to layer caching.

Once it's up:
- Eureka dashboard: <http://localhost:8761> — confirm all 6 apps show as `UP`.
- API entry point: <http://localhost:8080>
- Interactive API docs: <http://localhost:8080/swagger-ui.html> — one Swagger UI covering all four REST services, switchable via a dropdown in the top-right. Docs themselves are public; use the "Authorize" button to paste a Bearer token so "Try it out" works against protected endpoints directly.

Stop everything:
```bash
docker compose down
```

Stop and wipe all data (Postgres volume + Redis volume):
```bash
docker compose down -v
```

### Optional: set your own JWT secret

A development secret is baked into `application.yml` as a fallback. For anything beyond local testing, set your own before starting:

```bash
export JWT_SECRET="a-much-longer-random-secret-of-your-choosing"
docker compose up --build
```

---

## 5. Running locally without Docker

1. Start a local Postgres and create three databases: `user_db`, `product_db`, `order_db` (see `docker/postgres-init/init-databases.sql` for the exact statements), owned by a user matching `DB_USER`/`DB_PASSWORD` in each service's `application.yml` (defaults to `ecommerce` / `ecommerce`).
2. Start a local Redis on the default port `6379`.
3. Build everything from the root:
   ```bash
   mvn clean install -DskipTests
   ```
4. Start each service in its own terminal, roughly in this order:
   ```bash
   cd eureka-server   && mvn spring-boot:run
   cd user-service    && mvn spring-boot:run
   cd product-service && mvn spring-boot:run
   cd cart-service    && mvn spring-boot:run
   cd order-service   && mvn spring-boot:run
   cd notification-service && mvn spring-boot:run
   cd api-gateway     && mvn spring-boot:run
   ```
   Eureka should come up first and the gateway last, but Spring Cloud clients retry registration on their own, so getting the order slightly wrong won't hard-fail anything.

---

## 6. Trying it out (end-to-end walkthrough)

**For Postman or similar tools:**
- Import `postman/ShopStack.postman_collection.json` and `postman/ShopStack-Local.postman_environment.json` into Postman (or Insomnia/Bruno, which can both import Postman collections).
- Pick the "ShopStack - Local" environment, run Auth → Register (or Login) once, and `{{token}}`/`{{refresh_token}}` get captured automatically — every other request already references them, and `{{product_id}}`/`{{order_id}}` fill in the same way after you create a product or check out.

All requests below go through the gateway on `:8080`.

**1. Register a user**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"Password123!"}'
```
Response includes a JWT `token` (valid 15 minutes by default) and a `refreshToken` (valid 7 days, used to get a new access token without logging in again — see step 8). Save the access token:
```bash
TOKEN="paste-the-token-here"
```

**2. Create a product** (requires auth)
```bash
curl -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Mechanical Keyboard","description":"Hot-swappable, tactile","price":89.99,"stockQuantity":25,"category":"Electronics"}'
```
Note the returned `id`.

**3. Browse products** (public, no auth needed)
```bash
curl http://localhost:8080/api/products
curl http://localhost:8080/api/products/1
```

**4. Add the product to your cart**
```bash
curl -X POST http://localhost:8080/api/cart/items \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"quantity":2}'
```

**5. View your cart**
```bash
curl http://localhost:8080/api/cart -H "Authorization: Bearer $TOKEN"
```

**6. Checkout**
```bash
curl -X POST http://localhost:8080/api/orders/checkout \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"shippingAddress":"123 Main St","paymentMethod":"CREDIT_CARD","cardNumber":"4111111111111111","cardHolderName":"Alice Smith","expiryMonth":"12","expiryYear":"30"}'
```
`paymentMethod` is required (`CREDIT_CARD`, `PAYPAL`, or `CASH`). The card number above is the standard Luhn-valid test number — any real card number works the same way through the same check. No real payment gateway is involved: authorization is simulated, and orders over $1000 (`payment.decline-above-amount`) deterministically decline with a `402` so that path is actually testable. Checkout decrements stock in `product-service`, validates/processes payment, persists the order, and clears your cart — if payment fails after stock was already reserved, that stock is automatically released back to `product-service`. It also publishes an event to the `order-events` Redis Stream, which `notification-service` picks up a moment later:
```bash
docker compose logs -f notification-service
```
You should see a line like `Order confirmation -> user 1 | order 1 | 1 item(s) | total $89.99` show up within a second or two. This happens after the checkout response already came back — the client isn't waiting on it.

**7. View order history**
```bash
curl http://localhost:8080/api/orders -H "Authorization: Bearer $TOKEN"
```

**8. Refresh your access token (once it expires)**
```bash
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"paste-your-refresh-token-here"}'
```
Returns a new `token` and a new `refreshToken` — the old refresh token stops working the moment this succeeds, so hang on to the new one.

**9. Log out (revoke the refresh token server-side)**
```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"paste-your-refresh-token-here"}'
```
The access token still works until it naturally expires — it's stateless and can't be revoked early — but this refresh token is now dead.

---

## 7. API summary

| Method | Path                              | Auth required | Service          |
|--------|-------------------------------------|:--------------:|-------------------|
| POST   | `/api/auth/register`               | No             | user-service      |
| POST   | `/api/auth/login`                  | No             | user-service      |
| POST   | `/api/auth/refresh`                | No*            | user-service      |
| POST   | `/api/auth/logout`                 | No*            | user-service      |
| GET    | `/api/users/me`                    | Yes            | user-service      |
| GET    | `/api/users/{id}`                  | Yes            | user-service      |
| GET    | `/api/products`                    | No             | product-service   |
| GET    | `/api/products/{id}`               | No             | product-service   |
| POST   | `/api/products`                    | Yes            | product-service   |
| PUT    | `/api/products/{id}`                | Yes            | product-service   |
| DELETE | `/api/products/{id}`                | Yes            | product-service   |
| GET    | `/api/cart`                        | Yes            | cart-service      |
| POST   | `/api/cart/items`                  | Yes            | cart-service      |
| PUT    | `/api/cart/items/{productId}`       | Yes            | cart-service      |
| DELETE | `/api/cart/items/{productId}`       | Yes            | cart-service      |
| DELETE | `/api/cart`                        | Yes            | cart-service      |
| POST   | `/api/orders/checkout`             | Yes            | order-service     |
| GET    | `/api/orders`                      | Yes            | order-service     |
| GET    | `/api/orders/{id}`                  | Yes            | order-service     |
| POST   | `/api/orders/{id}/cancel`           | Yes            | order-service     |

"Auth required" means send `Authorization: Bearer <token>` — the gateway rejects the request with `401` before it reaches the backend if the token's missing, malformed, or expired.

\* `/api/auth/refresh` and `/api/auth/logout` don't need a Bearer token — you're using them because your access token is gone or expired, so they take the refresh token in the body instead: `{ "refreshToken": "..." }`.

`POST /api/orders/checkout` requires `paymentMethod` (`CREDIT_CARD`, `PAYPAL`, or `CASH`) in the body, plus the matching payment fields — see step 6 of the walkthrough above for a full example. No real payment gateway is involved; authorization is simulated.

---

## 8. Design notes

A few decisions worth explaining:

**Service discovery instead of hardcoded URLs.** Every service finds every other one through Eureka (`lb://SERVICE-NAME`), so nothing breaks if a service moves or scales to multiple instances.

**API Gateway pattern.** One public entry point, JWT validation happens once at the gateway, and downstream services just trust an `X-User-Id` header instead of each re-implementing token parsing.

**Database-per-service.** `user_db`, `product_db`, `order_db` are separate databases — no service reaches into another's tables directly.

**Checkout is synchronous, notifications aren't.** `order-service` calls `cart-service` and `product-service` directly and waits for real answers, because if a product is out of stock the client needs to know right now, not five seconds later. But sending an order confirmation doesn't need to block anything, so `order-service` just publishes an event to Redis Streams after the order is already committed and moves on — `notification-service` consumes it whenever it gets to it. A slow or fully-down `notification-service` can never delay or fail a real order. `OrderEventPublisher` in `order-service` is where this is enforced: it runs after the order is saved, wrapped in try/catch, so a Redis hiccup there never touches the checkout response.

**The notification consumer takes at-least-once delivery seriously.** `OrderEventListener` only acknowledges a message after it's successfully processed — if it crashes partway through, the message stays unacknowledged and `PendingMessageRecoveryJob` picks it up later. Since Redis Streams guarantees at-least-once (not exactly-once) delivery, `NotificationService` also tracks which order IDs it's already handled, so a redelivered message doesn't send a duplicate confirmation.

**Access tokens are short-lived; refresh tokens are the ones that can actually be revoked.** JWT access tokens expire in 15 minutes and can't be invalidated early since they're stateless. Refresh tokens are opaque random strings stored in Postgres instead, valid for 7 days, and rotated on every use — using one to get a new access token also kills it and issues a new one, so a stolen refresh token stops working the moment the real client refreshes.

**Multi-stage Docker builds.** Each service compiles inside a throwaway Maven container and ships only the final jar in a slim runtime image.

**Payment is validated for real, even though no real gateway is involved.** Checkout requires a `paymentMethod` and, for credit cards, runs the actual Luhn checksum algorithm plus expiry-date and format checks before anything is charged. Stock is decremented *before* payment is attempted (so the total is known and the reservation exists), and if payment then fails or declines, every item already decremented gets restocked before the error is returned — the order is never created and the customer is never charged for something that didn't go through. Authorization itself is simulated: card payments deterministically decline above a configurable amount (`payment.decline-above-amount`, default $1000) so that failure path is actually reachable in testing rather than random.

**Validation errors are reported all at once.** Every service's exception handler collects every failing field from a bad request instead of stopping at the first one, so a request with three problems doesn't take three separate round trips to fully diagnose.

**API docs are aggregated at the gateway, not scattered across four ports.** Each REST service generates its own OpenAPI spec via springdoc-openapi, but `user-service`, `product-service`, `cart-service`, and `order-service` aren't reachable from the host directly — only the gateway is. So the gateway proxies each service's raw `/v3/api-docs` through a public route and springdoc's Swagger UI aggregator ties them into one page with a dropdown, matching the "everything through the gateway" pattern used everywhere else in this project.

### Why Redis Streams and not Kafka

Kafka is the more commonly expected tool for the async notification flow, and would be a reasonable choice too. Redis Streams instead because it actually fit better here: Redis was already running and load-bearing for two other services, so there's no new infrastructure to stand up, and Spring Data Redis was already a dependency, so no new client library either. Its consumer groups (`XREADGROUP`/`XACK`, pending-entry tracking) give real at-least-once delivery with acknowledgment, not a toy version of it. Kafka's partition-based parallelism, log durability built for replaying events aren't things this project needs with one producer and one consumer.

### Known gaps

- Checkout uses a simple synchronous orchestration rather than a saga/outbox pattern — fine at this scale, but worth knowing if this ever needs to handle partial failures more gracefully. The stock-decrement-then-restock-on-payment-failure sequence is a manual, best-effort version of what a saga pattern would formalize.
- There's no rate limiting, circuit breakers, or distributed tracing wired in yet.
- Only one refresh token is valid per user at a time — logging in on a new device kills the old session, which wouldn't fly if you wanted real multi-device support.
- `notification-service` only logs a line instead of actually sending anything, though swapping that for a real integration wouldn't touch the reliability logic around it.
- `PendingMessageRecoveryJob` only recovers this exact consumer's own backlog — fine with a single replica, but a multi-replica deployment would need proper `XCLAIM`-based reclaim logic too.
- Restock calls made after a declined payment or a cancellation are themselves best-effort (logged on failure, not retried) — a genuinely bulletproof version would need the same kind of reliable-delivery mechanism `notification-service` uses for order events, not a plain synchronous Feign call.

---

## 9. Project layout

```
ecommerce-microservices/
├── pom.xml                     # Maven parent/reactor
├── docker-compose.yml
├── docker/postgres-init/       # DB bootstrap script
├── eureka-server/
├── api-gateway/
├── user-service/
├── product-service/
├── cart-service/
├── order-service/
├── notification-service/
├── postman/                     # Importable Postman collection + environment
├── README.md
└── PROJECT_GUIDE.md
```

See [PROJECT_GUIDE.md](./PROJECT_GUIDE.md) for the detailed file-by-file guide.
