# ---- Build stage ----
# Build context must be the project root (see docker-compose.yml): this is a
# multi-module Maven reactor, so the parent pom.xml and every module must be visible.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY pom.xml .
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY cart-service/pom.xml cart-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml

# Resolves and caches every dependency the reactor needs before any source file is
# copied in, so this layer stays cached across rebuilds that only touch eureka-server/src.
RUN mvn -B -pl eureka-server -am dependency:go-offline

COPY eureka-server/src eureka-server/src

RUN mvn -B -pl eureka-server -am package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build /workspace/eureka-server/target/eureka-server.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
