package com.ecommerce.order.service;

import com.ecommerce.order.client.CartDTO;
import com.ecommerce.order.client.resilient.ResilientCartClient;
import com.ecommerce.order.client.resilient.ResilientProductClient;
import com.ecommerce.order.dto.CheckoutRequest;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.event.OrderEventPublisher;
import com.ecommerce.order.exception.ApiException;
import com.ecommerce.order.logging.LogPerformance;
import com.ecommerce.order.payment.PaymentService;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ResilientCartClient cartClient;
    private final ResilientProductClient productClient;
    private final OrderEventPublisher orderEventPublisher;
    private final PaymentService paymentService;

    // cart -> decrement stock -> payment -> persist -> clear cart -> publish event.
    // If payment fails after stock was decremented, everything decremented is restocked.
    @Transactional
    @LogPerformance
    public Order checkout(Long userId, CheckoutRequest request) {
        CartDTO cart = cartClient.getCart(userId);

        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot checkout an empty cart");
        }
        if (request == null || request.paymentMethod() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "paymentMethod is required");
        }

        Order order = Order.builder()
                .userId(userId)
                .status(OrderStatus.PAID)
                .shippingAddress(request.shippingAddress())
                .build();

        BigDecimal total = BigDecimal.ZERO;
        List<CartDTO.CartItemDTO> decremented = new ArrayList<>();

        for (CartDTO.CartItemDTO cartItem : cart.items()) {
            // Key generated once, held fixed across any retries so a retry never double-applies.
            productClient.decrementStock(cartItem.productId(), cartItem.quantity(), newIdempotencyKey());
            decremented.add(cartItem);

            OrderItem orderItem = OrderItem.builder()
                    .productId(cartItem.productId())
                    .productName(cartItem.productName())
                    .unitPrice(cartItem.price())
                    .quantity(cartItem.quantity())
                    .build();
            order.addItem(orderItem);
            total = total.add(orderItem.subtotal());
        }

        String paymentSummary;
        try {
            paymentSummary = paymentService.validateAndProcess(request.paymentMethod(), total, request);
        } catch (RuntimeException paymentFailure) {
            releaseReservedStock(decremented);
            throw paymentFailure;
        }

        order.setPaymentSummary(paymentSummary);
        order.setTotalAmount(total);
        Order saved = orderRepository.save(order);

        cartClient.clearCart(userId);

        orderEventPublisher.publishOrderCreated(saved);

        return saved;
    }

    public List<Order> listForUser(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Order getById(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Order not found: " + orderId));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This order does not belong to you");
        }
        return order;
    }

    // PAID orders can still be cancelled (checkout goes straight to PAID, no pending state).
    // Only SHIPPED/CANCELLED are blocked. Cancelling restores the reserved stock.
    @Transactional
    public Order cancel(Long userId, Long orderId) {
        Order order = getById(userId, orderId);
        if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, "Orders that are already " + order.getStatus() + " cannot be cancelled");
        }

        List<OrderItem> items = order.getItems();
        releaseReservedStockForOrder(items);

        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }

    // Failures are logged, not thrown, so they never mask the original payment error.
    private void releaseReservedStock(List<CartDTO.CartItemDTO> items) {
        for (CartDTO.CartItemDTO item : items) {
            restockOne(item.productId(), item.quantity());
        }
    }

    private void releaseReservedStockForOrder(List<OrderItem> items) {
        for (OrderItem item : items) {
            restockOne(item.getProductId(), item.getQuantity());
        }
    }

    private void restockOne(Long productId, int quantity) {
        try {
            productClient.restock(productId, quantity, newIdempotencyKey());
        } catch (Exception restockFailure) {
            log.warn("Failed to restock product {} (qty {}) - stock may be understated until manually corrected",
                    productId, quantity, restockFailure);
        }
    }

    // Generated once by the initiator, passed as a plain argument into the resilience-wrapped
    // Feign call; see ResilientProductClient for why it can't be generated inside the retry.
    private String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }
}
