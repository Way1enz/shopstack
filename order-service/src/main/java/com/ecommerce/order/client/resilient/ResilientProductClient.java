package com.ecommerce.order.client.resilient;

import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.client.ProductDTO;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

// Instance name "product-service" matches application.yml. decrementStock/restock take
// a caller-supplied idempotencyKey rather than generating one internally: @Retry re-invokes
// this method from scratch on every attempt, so an internally generated key would change
// on every retry. OrderService fixes the key once, before the first attempt.
@Component
public class ResilientProductClient {

    private static final String INSTANCE = "product-service";

    private final ProductClient productClient;

    public ResilientProductClient(ProductClient productClient) {
        this.productClient = productClient;
    }

    // No fallback: 4xx (e.g. insufficient stock) is excluded from retry in application.yml,
    // so anything reaching here should propagate and let checkout() restock what it can.
    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE)
    public ProductDTO decrementStock(Long productId, int quantity, String idempotencyKey) {
        return productClient.decrementStock(productId, quantity, idempotencyKey);
    }

    // restock is itself a compensating action. OrderService.restockOne() already
    // catches/logs failures here, so no fallback needed at this layer either.
    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE)
    public ProductDTO restock(Long productId, int quantity, String idempotencyKey) {
        return productClient.restock(productId, quantity, idempotencyKey);
    }
}
