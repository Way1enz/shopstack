package com.ecommerce.order.client;

import java.math.BigDecimal;
import java.util.List;

public record CartDTO(
        Long userId,
        List<CartItemDTO> items
) {
    public record CartItemDTO(
            Long productId,
            String productName,
            BigDecimal price,
            Integer quantity
    ) {
    }
}
