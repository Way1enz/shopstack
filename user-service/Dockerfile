# syntax=docker/dockerfile:1
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
# copied in, so this layer stays cached across rebuilds that only touch user-service/src.
RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -pl user-service -am dependency:go-offline

COPY user-service/src user-service/src

RUN --mount=type=cache,target=/root/.m2,id=maven-cache \
    mvn -B -pl user-service -am package -DskipTests

FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build /workspace/user-service/target/user-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
