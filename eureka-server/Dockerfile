# ---- Build stage ----
# NOTE: this Dockerfile expects the build context to be the PROJECT ROOT
# (docker-compose.yml sets `context: .` and `dockerfile: eureka-server/Dockerfile`)
# because this is a multi-module Maven reactor project - the parent pom.xml
# and every module must be visible to resolve versions correctly.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY cart-service/pom.xml cart-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml

COPY eureka-server/src eureka-server/src

RUN mvn -B -pl eureka-server -am package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring
COPY --from=build /workspace/eureka-server/target/eureka-server.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
