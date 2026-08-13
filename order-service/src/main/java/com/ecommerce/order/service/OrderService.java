package com.ecommerce.order.service;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.CartDTO;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.event.OrderEventPublisher;
import com.ecommerce.order.exception.ApiException;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartClient cartClient;
    private final ProductClient productClient;
    private final OrderEventPublisher orderEventPublisher;

    // cart-service (read) -> product-service (decrement stock) -> persist Order -> clear cart
    // -> publish event (fire-and-forget, see OrderEventPublisher). Simple synchronous
    // orchestration - a production system might want a saga/outbox pattern here instead.
    @Transactional
    public Order checkout(Long userId, String shippingAddress) {
        CartDTO cart = cartClient.getCart(userId);

        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot checkout an empty cart");
        }

        Order order = Order.builder()
                .userId(userId)
                .status(OrderStatus.CREATED)
                .shippingAddress(shippingAddress)
                .build();

        BigDecimal total = BigDecimal.ZERO;

        for (CartDTO.CartItemDTO cartItem : cart.items()) {
            // Confirms availability and decrements stock at the moment of truth.
            productClient.decrementStock(cartItem.productId(), cartItem.quantity());

            OrderItem orderItem = OrderItem.builder()
                    .productId(cartItem.productId())
                    .productName(cartItem.productName())
                    .unitPrice(cartItem.price())
                    .quantity(cartItem.quantity())
                    .build();
            order.addItem(orderItem);
            total = total.add(orderItem.subtotal());
        }

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

    @Transactional
    public Order cancel(Long userId, Long orderId) {
        Order order = getById(userId, orderId);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new ApiException(HttpStatus.CONFLICT, "Only CREATED orders can be cancelled");
        }
        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }
}
