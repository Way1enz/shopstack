package com.ecommerce.order.client.resilient;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.CartDTO;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Instance name "cart-service" matches the circuitbreaker/retry keys in application.yml.
// Kept separate from OrderService so @CircuitBreaker/@Retry actually get AOP-proxied.
// Calling an annotated method on `this` bypasses the proxy and silently skips resilience.
@Component
public class ResilientCartClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientCartClient.class);
    private static final String INSTANCE = "cart-service";

    private final CartClient cartClient;

    public ResilientCartClient(CartClient cartClient) {
        this.cartClient = cartClient;
    }

    // No fallback: checkout can't proceed without a cart, so this propagates to a 503.
    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE)
    public CartDTO getCart(Long userId) {
        return cartClient.getCart(userId);
    }

    // Runs after the order is committed, so failure here must not fail the checkout.
    // Fallback swallows it. Safe to retry: clearing an empty cart is a no-op.
    @CircuitBreaker(name = INSTANCE, fallbackMethod = "clearCartFallback")
    @Retry(name = INSTANCE, fallbackMethod = "clearCartFallback")
    public void clearCart(Long userId) {
        cartClient.clearCart(userId);
    }

    @SuppressWarnings("unused") // invoked reflectively by resilience4j
    private void clearCartFallback(Long userId, Throwable t) {
        log.warn("Failed to clear cart for user {} - will remain until next successful checkout", userId, t);
    }
}
