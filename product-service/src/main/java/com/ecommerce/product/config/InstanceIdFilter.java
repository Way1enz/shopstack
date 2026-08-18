package com.ecommerce.product.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Demonstration-only: stamps every response with which container instance handled it,
// so scaling this service (docker compose up --scale product-service=3) and hitting it
// repeatedly through the gateway visibly shows requests rotating across instances via
// Spring Cloud LoadBalancer, instead of that load-balancing being invisible.
@Component
public class InstanceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Docker sets HOSTNAME to the container's short ID automatically - no extra config needed.
        String instanceId = System.getenv().getOrDefault("HOSTNAME", "unknown");
        response.setHeader("X-Instance-Id", instanceId);
        chain.doFilter(request, response);
    }
}
