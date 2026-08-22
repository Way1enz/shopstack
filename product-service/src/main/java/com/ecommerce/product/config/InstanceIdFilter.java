package com.ecommerce.product.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Demonstration-only: stamps responses with the container instance so scaling and hitting
// this service repeatedly shows load balancing across instances.
@Component
public class InstanceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Docker sets HOSTNAME to the container's short ID automatically, so no extra config is needed.
        String instanceId = System.getenv().getOrDefault("HOSTNAME", "unknown");
        response.setHeader("X-Instance-Id", instanceId);
        chain.doFilter(request, response);
    }
}
