package com.ecommerce.cart.client;

import java.math.BigDecimal;

/** Mirrors product-service's ProductResponse; only the fields cart-service actually needs. */
public record ProductDTO(
        Long id,
        String name,
        BigDecimal price,
        Integer stockQuantity
) {
}
