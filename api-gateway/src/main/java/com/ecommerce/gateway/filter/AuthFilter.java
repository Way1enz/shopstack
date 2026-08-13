package com.ecommerce.gateway.filter;

import com.ecommerce.gateway.security.JwtValidator;
import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

// Referenced as "- AuthFilter" in application.yml routes. Validates the Bearer token and, on
// success, replaces it with X-User-Id/X-Username headers so downstream services can trust them
// without re-validating the JWT themselves.
@Component
public class AuthFilter extends AbstractGatewayFilterFactory<AuthFilter.Config> {

    private final JwtValidator jwtValidator;

    public AuthFilter(JwtValidator jwtValidator) {
        super(Config.class);
        this.jwtValidator = jwtValidator;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String header = request.getHeaders().getFirst("Authorization");

            if (header == null || !header.startsWith("Bearer ")) {
                return unauthorized(exchange);
            }

            String token = header.substring(7);
            Claims claims = jwtValidator.validate(token);
            if (claims == null) {
                return unauthorized(exchange);
            }

            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Id", claims.getSubject())
                    .header("X-Username", String.valueOf(claims.get("username")))
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        };
    }

    private Mono<Void> unauthorized(org.springframework.web.server.ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    // No per-route config needed, but required by the GatewayFilterFactory contract.
    public static class Config {
    }
}
