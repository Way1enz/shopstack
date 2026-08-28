package com.ecommerce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

// Single public entry point: routes requests to the right service via Eureka and validates JWTs on protected routes.
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        // WebFlux/Reactor doesn't carry tracing context across async boundaries by default.
        Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
