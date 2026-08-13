package com.ecommerce.order.service;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.CartDTO;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.dto.CheckoutRequest;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.event.OrderEventPublisher;
import com.ecommerce.order.exception.ApiException;
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

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final CartClient cartClient;
    private final ProductClient productClient;
    private final OrderEventPublisher orderEventPublisher;
    private final PaymentService paymentService;

    // cart-service (read) -> decrement stock per item -> validate & process payment ->
    // persist Order -> clear cart -> publish event (fire-and-forget). If payment fails
    // AFTER stock was already decremented, everything already decremented is restocked
    // before the error propagates - mirrors a real authorize/reserve-then-capture flow:
    // never leave stock reserved for an order that was never actually paid for.
    @Transactional
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
            // Confirms availability and decrements stock at the moment of truth.
            productClient.decrementStock(cartItem.productId(), cartItem.quantity());
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

    // Checkout now goes straight to PAID (there's no separate "awaiting payment" state
    // in this synchronous flow - see checkout() above), so cancellation is allowed for
    // PAID orders too, not just CREATED. Only SHIPPED and already-CANCELLED orders are
    // blocked. Cancelling a paid order also releases its reserved stock back to
    // product-service, since checkout had decremented it.
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

    // Failures here are logged, not thrown - this runs both as compensation for an
    // already-failed payment (don't mask the original error) and as part of a
    // cancellation the user explicitly asked for.
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
            productClient.restock(productId, quantity);
        } catch (Exception restockFailure) {
            log.warn("Failed to restock product {} (qty {}) - stock may be understated until manually corrected",
                    productId, quantity, restockFailure);
        }
    }
}
