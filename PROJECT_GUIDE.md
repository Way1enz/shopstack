# Project Guide — what every file does

This companion doc walks through the repository file by file. Read
[README.md](./README.md) first for the big picture and how to run things;
come here when you want to know what a specific class or config file is
for.

---

## Root

| File | Purpose |
|---|---|
| `pom.xml` | The Maven **parent/reactor** POM. Declares the 7 modules, and centralizes version management (Spring Boot, Spring Cloud, JJWT, springdoc-openapi) via `<dependencyManagement>` so every service's `pom.xml` stays short and consistent. |
| `docker-compose.yml` | Orchestrates the whole platform: `postgres`, `redis`, and all 6 Spring Boot services, wired together on one Docker network with health-check-based startup ordering. |
| `docker/postgres-init/init-databases.sql` | Runs automatically the first time the `postgres` container starts. Creates the three logical databases (`user_db`, `product_db`, `order_db`) that the JPA-based services need. |
| `.gitignore` | Standard ignores for Maven build output, IDE files, logs, and local `.env` files. |
| `README.md` | Setup, run instructions, architecture diagram, API summary, curl walkthrough. |
| `PROJECT_GUIDE.md` | This file. |
| `postman/ShopStack.postman_collection.json`, `postman/ShopStack-Local.postman_environment.json` | Importable Postman/Bruno/Insomnia collection covering every endpoint. Register/Login/Refresh all auto-capture `token` and `refresh_token` into environment variables via post-response test scripts, and Create Product / Checkout auto-capture `product_id` / `order_id` the same way — see the "Common patterns" note at the bottom of this file for why that matters, and the root README's "Trying it out" section for the click-through workflow. |

---

## `eureka-server/` — service registry

| File | Purpose |
|---|---|
| `pom.xml` | Depends on `spring-cloud-starter-netflix-eureka-server` + actuator. |
| `EurekaServerApplication.java` | `@EnableEurekaServer` — turns this Spring Boot app into a Eureka registry. Every other service registers itself here on boot and queries it to find other services. |
| `application.yml` | Port `8761`. Disables `register-with-eureka`/`fetch-registry` on itself (the registry doesn't need to register with itself). |
| `Dockerfile` | Multi-stage build: compiles the whole reactor inside a `maven:3.9-eclipse-temurin-17` container (needs the full reactor because it's a multi-module project), then copies just the built jar into a slim `eclipse-temurin:17-jre-alpine` runtime image. |

---

## `api-gateway/` — single public entry point

| File | Purpose |
|---|---|
| `pom.xml` | `spring-cloud-starter-gateway`, Eureka client, JJWT (to validate tokens), and `springdoc-openapi-starter-webflux-ui` (the reactive flavor, matching Gateway's WebFlux stack) for the aggregated Swagger UI. |
| `ApiGatewayApplication.java` | Plain Spring Boot bootstrap class. |
| `security/JwtValidator.java` | Parses and verifies a JWT's signature/expiry using the shared `jwt.secret`. Returns the token's `Claims` if valid, `null` otherwise. |
| `filter/AuthFilter.java` | A custom `GatewayFilterFactory` referenced in `application.yml` as `- AuthFilter` on protected routes. Reads the `Authorization` header, rejects with `401` if missing/invalid, and on success **replaces** it with trusted `X-User-Id` / `X-Username` headers before forwarding the request downstream. This means backend services never parse a JWT themselves — they just trust the header, because only the gateway can set it. |
| `application.yml` | Defines every route: which path patterns go to which service (via Eureka's `lb://SERVICE-NAME` load-balanced URIs), and which routes get the `AuthFilter` applied (product **reads** are public, product **writes** and everything under `/api/cart` and `/api/orders` require a token). Also defines four public `*-docs` routes that proxy each service's raw `/v3/api-docs` through the gateway (with a `RewritePath` filter stripping the service-name prefix), and a `springdoc.swagger-ui.urls` list tying all four into one aggregated Swagger UI at `/swagger-ui.html`. None of the docs machinery needs its own Gateway route beyond that — requests to `/swagger-ui.html` itself and its static assets don't match any route predicate, so Spring Cloud Gateway lets them fall through to the gateway's own local WebFlux handlers, which is where springdoc's UI actually lives. |
| `Dockerfile` | Same multi-stage pattern as above. |

---

## `user-service/` — accounts & authentication

| File | Purpose |
|---|---|
| `pom.xml` | Web, JPA, Validation, Security (for `BCryptPasswordEncoder` only), Eureka client, Postgres driver, JJWT, `springdoc-openapi-starter-webmvc-ui` (Swagger/OpenAPI), Lombok. |
| `UserServiceApplication.java` | Bootstrap class. |
| `config/OpenApiConfig.java` | Sets the service's title/description in its generated OpenAPI spec, and registers a `bearerAuth` security scheme so the gateway's aggregated Swagger UI shows an "Authorize" button for this service's protected endpoints. Same pattern repeated in `product-service`, `cart-service`, and `order-service`. |
| `entity/User.java` | JPA entity — `id`, `username`, `email`, hashed `password`, `role`, `createdAt`. |
| `entity/RefreshToken.java` | JPA entity for the refresh token store — `token` (random UUID string, NOT a JWT), `user` (owner), `expiryDate`, `revoked`, `createdAt`. Unlike the stateless JWT access token, this one lives in Postgres, which is what makes it possible to actually revoke (see `/api/auth/logout`). |
| `repository/UserRepository.java` | Spring Data JPA repository with `findByUsername`, `existsByUsername`, `existsByEmail`. |
| `repository/RefreshTokenRepository.java` | Spring Data JPA repository with `findByToken` and `deleteByUser`. |
| `security/JwtUtil.java` | Issues signed **access token** JWTs on successful register/login/refresh. Subject = user id; custom claims = `username`, `role`. Signed with `jwt.secret` (same secret the gateway uses to verify). Expiry is short (15 min default via `jwt.expiration-ms`) since this token can't be revoked early. |
| `security/SecurityConfig.java` | Registers a `BCryptPasswordEncoder` bean and disables Spring Security's default filter chain protections (CSRF, sessions) — auth enforcement happens at the gateway, not here. |
| `dto/RegisterRequest.java`, `LoginRequest.java`, `RefreshRequest.java`, `LogoutRequest.java`, `AuthResponse.java`, `UserResponse.java`, `ErrorResponse.java` | Request/response records, kept separate from the JPA entity so the password hash is never serialized back to a client. `AuthResponse` carries both `token` (access) and `refreshToken`. `RegisterRequest`'s `username` has a `@Pattern` restricting it to letters/digits/`_`/`.`/`-`, and `password` uses the custom `@StrongPassword` constraint below. |
| `validation/StrongPassword.java`, `StrongPasswordValidator.java` | A custom Bean Validation constraint enforcing password complexity (8+ chars, uppercase, lowercase, digit, special character, no whitespace). Unlike a plain `@Pattern`, the validator collects every failing rule and reports them together in one message (`"must contain an uppercase letter, a digit, ..."`) rather than a single generic "invalid password." |
| `service/RefreshTokenService.java` | Issues refresh tokens (`createRefreshToken` — deletes any existing one for that user first, so only one is ever valid at a time), and verifies them (`verify` — throws and deletes the row if expired/revoked, so a dead token can never be replayed even once). |
| `service/UserService.java` | Business logic: `register`/`login` both call the shared private `issueTokens(user)` helper, which returns a fresh access token *and* a fresh refresh token together. `refresh(refreshTokenValue)` looks up and verifies the refresh token, then calls `issueTokens` again — this is what makes it "rotating": every refresh also replaces the refresh token itself, not just the access token. `logout(refreshTokenValue)` deletes the row outright. |
| `controller/AuthController.java` | Public routes: `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`. None of these require a Bearer access token — `/refresh` and `/logout` instead take the refresh token in the request body, since the whole point of `/refresh` is recovering from an *expired* access token. |
| `controller/UserController.java` | Protected routes: `GET /api/users/me` (reads the gateway-injected `X-User-Id` header), `GET /api/users/{id}`. |
| `exception/ApiException.java`, `GlobalExceptionHandler.java` | A small custom runtime exception carrying an HTTP status, plus a `@RestControllerAdvice` that turns it (and bean-validation failures) into consistent JSON error responses. |
| `application.yml` | Port `8081`. Postgres connection to `user_db`. Eureka registration. `jwt.expiration-ms` (access token, 15 min default) and `jwt.refresh-expiration-ms` (refresh token, 7 days default) are separate settings. |
| `Dockerfile` | Multi-stage build. |

---

## `product-service/` — catalog, with Redis as a cache

| File | Purpose |
|---|---|
| `pom.xml` | Web, JPA, Validation, **Data Redis + Cache**, Eureka client, Postgres driver, `springdoc-openapi-starter-webmvc-ui`, Lombok. |
| `ProductServiceApplication.java` | `@EnableCaching` — turns on Spring's cache abstraction, backed by Redis (configured below). |
| `config/OpenApiConfig.java` | Swagger/OpenAPI metadata + `bearerAuth` security scheme (see the same file in `user-service` for the full explanation). |
| `entity/Product.java` | JPA entity — `name`, `description`, `price`, `stockQuantity`, `category`, `imageUrl`, timestamps. Implements `Serializable` since cached values pass through JSON serialization. |
| `repository/ProductRepository.java` | Spring Data JPA repository with category/name search + built-in pagination support. |
| `config/RedisConfig.java` | Defines the `CacheManager` bean: JSON serialization (via Jackson, so cached entries are human-readable in `redis-cli`), 10-minute TTL, null values not cached. |
| `dto/ProductRequest.java`, `ProductResponse.java`, `ErrorResponse.java` | Request/response records with Bean Validation annotations on the request side. |
| `service/ProductService.java` | Business logic. `getById` is annotated `@Cacheable("products")` — first call hits Postgres and populates Redis; subsequent calls for the same id are served straight from Redis until the TTL expires or the entry is evicted. `update`/`delete`/`decrementStock`/`restock` are `@CacheEvict` so the cache never serves stale data after a write. `decrementStock` is the method `order-service` calls during checkout — it throws a `409 Conflict` if stock is insufficient. `restock` is its inverse, called by `order-service` when a payment declines after stock was already reserved, or when a paid order is cancelled. |
| `controller/ProductController.java` | `GET /api/products` (paginated, filterable by `category`/`search`), `GET /api/products/{id}` (cached), `POST`/`PUT`/`DELETE` (mutations, protected by the gateway), and two internal endpoints used only by `order-service`: `POST /api/products/{id}/decrement-stock` and `POST /api/products/{id}/restock`. |
| `exception/` | Same pattern as user-service. |
| `application.yml` | Port `8082`. Postgres connection to `product_db`. Redis host/port. `spring.cache.type: redis`. |
| `Dockerfile` | Multi-stage build. |

---

## `cart-service/` — shopping cart, Redis as the *only* datastore

| File | Purpose |
|---|---|
| `pom.xml` | Web, Validation, **Data Redis**, Eureka client, **OpenFeign** (to call product-service), `springdoc-openapi-starter-webmvc-ui`, Lombok. Notably **no** JPA/Postgres dependency — this service has no relational database at all. |
| `CartServiceApplication.java` | `@EnableFeignClients` so the `ProductClient` interface below gets a working implementation at startup. |
| `config/OpenApiConfig.java` | Swagger/OpenAPI metadata + `bearerAuth` security scheme (see the same file in `user-service` for the full explanation). |
| `model/CartItem.java`, `Cart.java` | Plain POJOs (not JPA entities) representing a cart and its line items. These are what get JSON-serialized into Redis. |
| `config/RedisConfig.java` | Defines a `RedisTemplate<String, Cart>` bean: string keys, JSON values (via `GenericJackson2JsonRedisSerializer`). |
| `repository/CartRepository.java` | Not a Spring Data repository — a thin, explicit wrapper around `RedisTemplate`. `findByUserId` / `save` / `deleteByUserId` operate on a single key `cart:{userId}`. `save` sets a TTL (`cart.ttl-hours`, default 72h) so abandoned carts expire on their own — no cleanup job needed. |
| `client/ProductDTO.java`, `ProductClient.java` | A Feign client interface (`@FeignClient(name = "product-service")`) resolved dynamically through Eureka. Used to validate a product exists and snapshot its current name/price/stock when adding it to a cart. |
| `dto/AddItemRequest.java`, `UpdateQuantityRequest.java`, `ErrorResponse.java` | Request/response records. |
| `service/CartService.java` | Business logic: `getCart` (returns an empty cart if none exists yet — no 404), `addItem` (calls `product-service`, checks stock, merges quantities if the product is already in the cart), `updateQuantity`, `removeItem`, `clearCart`. |
| `controller/CartController.java` | `GET/POST/PUT/DELETE /api/cart...` — all read the gateway-injected `X-User-Id` header to know whose cart to operate on. |
| `exception/` | Same pattern as the other services, plus a handler for `FeignException.NotFound` (product doesn't exist) mapped to a clean `404`. |
| `application.yml` | Port `8083`. Redis host/port. Cart TTL. Eureka registration. |
| `Dockerfile` | Multi-stage build. |

---

## `order-service/` — checkout orchestration & order history

| File | Purpose |
|---|---|
| `pom.xml` | Web, JPA, Validation, Eureka client, **OpenFeign**, **Data Redis** (for stream publishing), Postgres driver, `springdoc-openapi-starter-webmvc-ui`, Lombok. |
| `OrderServiceApplication.java` | `@EnableFeignClients`. |
| `config/OpenApiConfig.java` | Swagger/OpenAPI metadata + `bearerAuth` security scheme (see the same file in `user-service` for the full explanation). |
| `entity/OrderStatus.java` | Enum: `CREATED`, `PAID`, `SHIPPED`, `CANCELLED`. Checkout now goes straight to `PAID` (payment is validated synchronously before an order is ever persisted — see `service/OrderService.java` below), so `CREATED` currently isn't reachable in practice; it's kept for a possible future async/pending-payment flow. |
| `entity/Order.java`, `OrderItem.java` | JPA entities — an `Order` has a total, status, shipping address, a `paymentSummary` (e.g. `"Credit card ending 1111"`, set by `payment/PaymentService`), and a `@OneToMany` list of `OrderItem`s (each a frozen snapshot of product id/name/price/quantity at the time of purchase, independent of later catalog changes). |
| `repository/OrderRepository.java` | Spring Data JPA repository, `findByUserIdOrderByCreatedAtDesc`. |
| `client/ProductDTO.java`, `ProductClient.java` | Feign client to `product-service` — fetches product details, and calls the internal `decrement-stock` and `restock` endpoints during checkout and cancellation. |
| `client/CartDTO.java`, `CartClient.java` | Feign client to `cart-service`. Because this is a direct **internal** service-to-service call that bypasses the gateway (and therefore bypasses `AuthFilter`), `order-service` sets the `X-User-Id` header itself when calling `cart-service` — acting as a trusted internal caller. |
| `event/OrderEventPublisher.java` | Publishes an `order-events` message to Redis Streams *after* the order is already committed and the cart is already cleared. Wrapped in try/catch so a Redis hiccup here can never fail an already-successful checkout — see the design notes in the root README for why this is deliberately outside the critical path. |
| `payment/PaymentMethod.java` | Enum: `CREDIT_CARD`, `PAYPAL`, `CASH`. |
| `payment/PaymentService.java` | Validates payment details and simulates authorization — no real payment gateway is involved. For `CREDIT_CARD`: validates the card number format and checks it against the Luhn algorithm, validates the expiry date hasn't passed, then deterministically declines (`402 Payment Required`) if the order total exceeds a configurable threshold (`payment.decline-above-amount`, default $1000) so the decline path is actually testable. Malformed input (bad card number, missing fields, expired card) is a `400`; a structurally valid but declined card is a `402` — the two are deliberately different status codes. |
| `dto/CheckoutRequest.java` | Now carries payment details alongside `shippingAddress`: a required `paymentMethod`, plus method-specific fields (`cardNumber`/`cardHolderName`/`expiryMonth`/`expiryYear` for `CREDIT_CARD`, `paypalEmail` for `PAYPAL`) that are validated imperatively in `PaymentService` rather than declaratively, since "required only if paymentMethod is X" isn't something Bean Validation expresses cleanly on a single field. |
| `dto/OrderResponse.java`, `OrderItemResponse.java`, `ErrorResponse.java` | Request/response records. `OrderResponse` includes `paymentSummary`. |
| `service/OrderService.java` | The core orchestration: `checkout(userId, request)` fetches the cart from `cart-service`, decrements stock for each line item via `product-service`, THEN validates/processes payment. If payment fails after stock was already decremented, every already-decremented item is restocked before the error propagates — mirrors a real authorize/reserve-then-capture flow, and matches how a console-app precursor to this project (CoffeeShop) handled the same sequencing. Only on successful payment is the `Order` persisted (as `PAID`), the cart cleared, and the event published. `cancel(userId, orderId)` allows cancelling `CREATED` or `PAID` orders (blocks only `SHIPPED`/already-`CANCELLED`) and releases the order's reserved stock back to `product-service` as part of cancelling. |
| `controller/OrderController.java` | `POST /api/orders/checkout` (now requires a full `CheckoutRequest` body with `paymentMethod`), `GET /api/orders`, `GET /api/orders/{id}`, `POST /api/orders/{id}/cancel`. |
| `exception/` | Same pattern, plus a `FeignException` handler that maps downstream service failures to a sensible status code (falls back to `502 Bad Gateway`). The validation handler reports every invalid field at once rather than just the first one. |
| `application.yml` | Port `8084`. Postgres connection to `order_db`. Redis host/port (for stream publishing only — order-service has no cache and no Redis-backed data). Eureka registration. `payment.decline-above-amount` (default $1000, overridable via `PAYMENT_DECLINE_ABOVE_AMOUNT`). |
| `Dockerfile` | Multi-stage build. |

---

## `notification-service/` — background Redis Streams consumer

Unlike every other service in this project, `notification-service` has **no
REST controllers and no `AuthFilter`-protected routes** — nothing calls it
directly. It exists purely to consume the `order-events` stream that
`order-service` publishes to and simulate sending an order confirmation.
It still registers with Eureka (so it's visible in the dashboard and
consistent with the rest of the project) even though nothing looks it up
there.

| File | Purpose |
|---|---|
| `pom.xml` | Web (needed for the actuator health endpoint to actually be exposed over HTTP), Data Redis, Actuator, Eureka client. No Postgres, no JPA — this service has no relational data at all. |
| `NotificationServiceApplication.java` | `@EnableScheduling`, so `PendingMessageRecoveryJob`'s `@Scheduled` method actually runs. |
| `config/RedisStreamConfig.java` | Defines the stream key (`order-events`), consumer group name, and a **fixed** consumer name constant (`notification-consumer-1` — deliberately not random/hostname-based, so a crashed-and-restarted container re-registers as the same consumer and can recover its own backlog). On startup, creates the consumer group if it doesn't already exist (`XGROUP CREATE ... MKSTREAM` equivalent, catching the `BUSYGROUP` error if it's already there), then builds and starts a `StreamMessageListenerContainer` subscribed with manual acknowledgment. |
| `listener/OrderEventListener.java` | The live consumer. Calls `NotificationService`, and only acknowledges the message (`XACK`) *after* that call returns successfully. If it throws, the exception is caught and logged, and the message is deliberately left unacknowledged — `PendingMessageRecoveryJob` is what retries it. |
| `service/NotificationService.java` | The actual "send" logic (currently just a log line — swap for a real email/SMS/push integration without touching anything else). Guards against double-processing with a Redis Set of already-handled order IDs, since Redis Streams consumer groups guarantee **at-least-once** delivery — a message can legitimately be redelivered, and without this check a customer could get duplicate confirmations. |
| `job/PendingMessageRecoveryJob.java` | A `@Scheduled` job (every 15s) that re-reads this consumer's own pending/unacknowledged backlog (`XREADGROUP` with ID `"0"` under the same consumer name returns a consumer's own history rather than new messages — standard Redis behavior, not a custom queue) and reprocesses anything found. This is what makes a mid-processing crash-and-restart safe: the message was never removed from the stream, just waiting. |
| `application.yml` | Port `8085`. Redis host/port. Eureka registration. |
| `Dockerfile` | Multi-stage build, identical pattern to every other service. |

**A note on a real bug hit while building this:** `RedisTemplate.opsForStream()`
is a *generic method* (`<HK, HV> StreamOperations<K, HK, HV> opsForStream()`)
independent of `StringRedisTemplate`'s own fixed `String, String` types.
Chaining a call directly off it (e.g.
`redisTemplate.opsForStream().read(...)`) can leave the compiler unable to
infer `HK`/`HV` from context, silently defaulting to `Object` and causing a
real compile error at the assignment. The fix used everywhere in this
project: assign `opsForStream()` to an explicitly-typed
`StreamOperations<String, String, String>` local variable first, then call
`.read()`/`.acknowledge()`/`.add()` on *that* variable instead of chaining.

---

## Common patterns used across every service

- **DTOs are Java `record`s** — immutable, concise, and they keep the
  HTTP contract separate from the JPA entity (so, for example,
  `user-service` never accidentally serializes a password hash back to a
  client).
- **`ApiException` + `@RestControllerAdvice`** — every service has its own
  small copy of this pair (no shared library, on purpose — see below) so
  errors come back as consistent JSON: `{ "timestamp", "status", "message" }`.
- **`@Value`-injected config with sane defaults** — every `application.yml`
  setting that varies between local/dev/Docker (DB host, Redis host,
  Eureka URI, JWT secret) is `${ENV_VAR:default}`, so the same jar runs
  correctly both on your laptop and inside `docker-compose` without any
  code changes.
- **No shared/common Maven module** — each service duplicates its own
  small DTOs (`ProductDTO` appears in both `cart-service` and
  `order-service`, for instance) rather than depending on a shared
  library. This is a deliberate microservices trade-off: a little
  duplication in exchange for services that can be built, versioned, and
  deployed completely independently of each other.
