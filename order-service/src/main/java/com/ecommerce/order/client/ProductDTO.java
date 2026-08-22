package com.ecommerce.order.client;

import java.math.BigDecimal;

/** Mirrors product-service's ProductResponse; only the fields order-service needs. */
public record ProductDTO(
        Long id,
        String name,
        BigDecimal price,
        Integer stockQuantity
) {
}
