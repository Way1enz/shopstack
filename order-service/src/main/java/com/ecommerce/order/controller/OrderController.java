package com.ecommerce.order.controller;

import com.ecommerce.order.dto.CheckoutRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * All endpoints are reached only after the gateway's AuthFilter validates
 * the JWT and forwards the caller's id as X-User-Id.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(@RequestHeader("X-User-Id") Long userId,
                                                    @RequestBody(required = false) CheckoutRequest request) {
        String address = request != null ? request.shippingAddress() : null;
        Order order = orderService.checkout(userId, address);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping
    public java.util.List<OrderResponse> listMyOrders(@RequestHeader("X-User-Id") Long userId) {
        return orderService.listForUser(userId).stream().map(OrderResponse::from).toList();
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return OrderResponse.from(orderService.getById(userId, id));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return OrderResponse.from(orderService.cancel(userId, id));
    }
}
