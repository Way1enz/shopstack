# syntax=docker/dockerfile:1
#
# One Dockerfile, multiple named stages, selected per service via docker-compose.yml's
# `target:`. The `deps` stage resolves the whole reactor once, unrestricted (no `-pl`),
# covering every service's dependencies. Each `build-*` stage extends it and adds only
# its own service's `src` and packaging.
#
# Build context must be the project root (see docker-compose.yml): this is a multi-module
# Maven reactor, so the parent pom.xml and every module's pom.xml must be visible.

FROM maven:3.9-eclipse-temurin-25 AS deps
WORKDIR /workspace

COPY pom.xml .
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY cart-service/pom.xml cart-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml

# No -pl: resolves the whole reactor, not one module. Retry flag covers transient
# transfer failures against Maven Central.
RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -Dmaven.wagon.http.retryHandler.count=3 dependency:go-offline

# ---- Per-service build stages: each adds only its own src, no cross-module dependency ----

FROM deps AS build-eureka-server
COPY eureka-server/src eureka-server/src
RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -Dmaven.wagon.http.retryHandler.count=3 -pl eureka-server -am package -DskipTests

FROM deps AS build-api-gateway
COPY api-gateway/src api-gateway/src
RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -Dmaven.wagon.http.retryHandler.count=3 -pl api-gateway -am package -DskipTests

FROM deps AS build-user-service
COPY user-service/src user-service/src
RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -Dmaven.wagon.http.retryHandler.count=3 -pl user-service -am package -DskipTests

FROM deps AS build-product-service
COPY product-service/src product-service/src
RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -Dmaven.wagon.http.retryHandler.count=3 -pl product-service -am package -DskipTests

FROM deps AS build-cart-service
COPY cart-service/src cart-service/src
RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -Dmaven.wagon.http.retryHandler.count=3 -pl cart-service -am package -DskipTests

FROM deps AS build-order-service
COPY order-service/src order-service/src
RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -Dmaven.wagon.http.retryHandler.count=3 -pl order-service -am package -DskipTests

FROM deps AS build-notification-service
COPY notification-service/src notification-service/src
RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -Dmaven.wagon.http.retryHandler.count=3 -pl notification-service -am package -DskipTests

# ---- Per-service runtime stages ----

FROM eclipse-temurin:25-jre-jammy AS runtime-eureka-server
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build-eureka-server /workspace/eureka-server/target/eureka-server.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:25-jre-jammy AS runtime-api-gateway
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build-api-gateway /workspace/api-gateway/target/api-gateway.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:25-jre-jammy AS runtime-user-service
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build-user-service /workspace/user-service/target/user-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:25-jre-jammy AS runtime-product-service
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build-product-service /workspace/product-service/target/product-service.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:25-jre-jammy AS runtime-cart-service
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build-cart-service /workspace/cart-service/target/cart-service.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:25-jre-jammy AS runtime-order-service
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build-order-service /workspace/order-service/target/order-service.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:25-jre-jammy AS runtime-notification-service
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build-notification-service /workspace/notification-service/target/notification-service.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
