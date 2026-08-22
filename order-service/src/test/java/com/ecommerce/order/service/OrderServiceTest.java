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
import com.ecommerce.order.payment.PaymentMethod;
import com.ecommerce.order.payment.PaymentService;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ResilientCartClient cartClient;
    @Mock
    private ResilientProductClient productClient;
    @Mock
    private OrderEventPublisher orderEventPublisher;
    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private OrderService orderService;

    @Test
    void checkout_emptyCart_throwsBadRequest() {
        when(cartClient.getCart(1L)).thenReturn(new CartDTO(1L, List.of()));
        CheckoutRequest request = new CheckoutRequest(null, PaymentMethod.CASH, null, null, null, null, null);

        assertThatThrownBy(() -> orderService.checkout(1L, request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkout_missingPaymentMethod_throwsBadRequest() {
        CartDTO.CartItemDTO item = new CartDTO.CartItemDTO(10L, "Widget", new BigDecimal("9.99"), 1);
        when(cartClient.getCart(1L)).thenReturn(new CartDTO(1L, List.of(item)));
        CheckoutRequest request = new CheckoutRequest(null, null, null, null, null, null, null);

        assertThatThrownBy(() -> orderService.checkout(1L, request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkout_happyPath_decrementsStockPersistsOrderClearsCartPublishesEvent() {
        Long userId = 1L;
        CartDTO.CartItemDTO item = new CartDTO.CartItemDTO(10L, "Widget", new BigDecimal("19.99"), 2);
        CartDTO cart = new CartDTO(userId, List.of(item));
        CheckoutRequest request = new CheckoutRequest("123 Main St", PaymentMethod.CASH, null, null, null, null, null);

        when(cartClient.getCart(userId)).thenReturn(cart);
        when(paymentService.validateAndProcess(eq(PaymentMethod.CASH), any(BigDecimal.class), eq(request)))
                .thenReturn("Cash");
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.checkout(userId, request);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(result.getPaymentSummary()).isEqualTo("Cash");
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("39.98"));
        assertThat(result.getItems()).hasSize(1);

        verify(productClient).decrementStock(eq(10L), eq(2), anyString());
        verify(cartClient).clearCart(userId);
        verify(orderEventPublisher).publishOrderCreated(result);
        verify(productClient, never()).restock(anyLong(), anyInt(), anyString());
    }

    @Test
    void checkout_paymentDeclines_restocksEverythingAlreadyDecrementedAndRethrows() {
        Long userId = 1L;
        CartDTO.CartItemDTO item1 = new CartDTO.CartItemDTO(10L, "Widget", new BigDecimal("19.99"), 2);
        CartDTO.CartItemDTO item2 = new CartDTO.CartItemDTO(20L, "Gadget", new BigDecimal("5.00"), 1);
        CartDTO cart = new CartDTO(userId, List.of(item1, item2));
        CheckoutRequest request = new CheckoutRequest(null, PaymentMethod.CREDIT_CARD,
                "4111111111111111", "Alice", "12", "30", null);

        when(cartClient.getCart(userId)).thenReturn(cart);
        ApiException declineException = new ApiException(HttpStatus.PAYMENT_REQUIRED, "Card declined");
        when(paymentService.validateAndProcess(eq(PaymentMethod.CREDIT_CARD), any(BigDecimal.class), eq(request)))
                .thenThrow(declineException);

        assertThatThrownBy(() -> orderService.checkout(userId, request))
                .isSameAs(declineException);

        // Stock was already decremented for both items before payment ran - both must be released.
        verify(productClient).decrementStock(eq(10L), eq(2), anyString());
        verify(productClient).decrementStock(eq(20L), eq(1), anyString());
        verify(productClient).restock(eq(10L), eq(2), anyString());
        verify(productClient).restock(eq(20L), eq(1), anyString());

        // Nothing downstream of payment should have happened.
        verify(orderRepository, never()).save(any(Order.class));
        verify(cartClient, never()).clearCart(any());
        verify(orderEventPublisher, never()).publishOrderCreated(any());
    }

    @Test
    void checkout_restockItselfFails_doesNotMaskOriginalPaymentError() {
        // The restock call failing must never hide the real payment exception (restockOne() catches and logs).
        Long userId = 1L;
        CartDTO.CartItemDTO item = new CartDTO.CartItemDTO(10L, "Widget", new BigDecimal("19.99"), 1);
        CartDTO cart = new CartDTO(userId, List.of(item));
        CheckoutRequest request = new CheckoutRequest(null, PaymentMethod.CASH, null, null, null, null, null);

        when(cartClient.getCart(userId)).thenReturn(cart);
        ApiException declineException = new ApiException(HttpStatus.PAYMENT_REQUIRED, "Declined");
        when(paymentService.validateAndProcess(eq(PaymentMethod.CASH), any(BigDecimal.class), eq(request)))
                .thenThrow(declineException);
        when(productClient.restock(eq(10L), eq(1), anyString())).thenThrow(new RuntimeException("product-service unreachable"));

        assertThatThrownBy(() -> orderService.checkout(userId, request))
                .isSameAs(declineException);
    }

    @Test
    void cancel_shippedOrder_throwsConflict() {
        Order order = Order.builder().id(1L).userId(1L).status(OrderStatus.SHIPPED).items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancel_alreadyCancelledOrder_throwsConflict() {
        Order order = Order.builder().id(1L).userId(1L).status(OrderStatus.CANCELLED).items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancel_paidOrder_restocksItemsAndSetsCancelled() {
        OrderItem item = OrderItem.builder().productId(10L).quantity(2).build();
        Order order = Order.builder().id(1L).userId(1L).status(OrderStatus.PAID)
                .items(new ArrayList<>(List.of(item))).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.cancel(1L, 1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(productClient).restock(eq(10L), eq(2), anyString());
    }

    @Test
    void getById_wrongUser_throwsForbidden() {
        Order order = Order.builder().id(1L).userId(2L).status(OrderStatus.PAID).items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.getById(1L, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getById_notFound_throwsNotFound() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getById(1L, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
