package com.ecommerce.order.dto;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        Long userId,
        BigDecimal totalAmount,
        OrderStatus status,
        String shippingAddress,
        String paymentSummary,
        List<OrderItemResponse> items,
        Instant createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getShippingAddress(),
                order.getPaymentSummary(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getCreatedAt()
        );
    }
}
