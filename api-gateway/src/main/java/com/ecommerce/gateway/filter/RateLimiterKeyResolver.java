package com.ecommerce.gateway.filter;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

// Bean name "rateLimiterKeyResolver" is referenced from application.yml as
// "#{@rateLimiterKeyResolver}". Rate-limits per user where possible, falling back to IP for
// routes AuthFilter never touches. AuthFilter must be listed first on any route with both,
// so X-User-Id is on the request by the time this runs.
@Component
public class RateLimiterKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return Mono.just("user:" + userId);
        }

        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        String ip = (remoteAddress != null && remoteAddress.getAddress() != null)
                ? remoteAddress.getAddress().getHostAddress()
                : "unknown";
        return Mono.just("ip:" + ip);
    }
}
