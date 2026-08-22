package com.ecommerce.order.integration;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.CartDTO;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.client.ProductDTO;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// CartClient/ProductClient are mocked - this tests order-service's own persistence logic,
// not cart-service or product-service. No Redis either: OrderEventPublisher is fire-and-forget,
// so an order should persist fine without it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class OrderIntegrationTest {

    // Same image docker-compose.yml uses.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private CartClient cartClient;

    @MockitoBean
    private ProductClient productClient;

    @Test
    void checkout_cashPayment_persistsOrderEvenWithoutRedisAvailable() throws Exception {
        Long userId = 100L;
        CartDTO.CartItemDTO item = new CartDTO.CartItemDTO(1L, "Widget", new BigDecimal("19.99"), 2);
        when(cartClient.getCart(userId)).thenReturn(new CartDTO(userId, List.of(item)));
        when(productClient.decrementStock(eq(1L), eq(2), anyString())).thenReturn(new ProductDTO(1L, "Widget", new BigDecimal("19.99"), 8));

        String body = "{\"shippingAddress\":\"123 Main St\",\"paymentMethod\":\"CASH\"}";

        mockMvc.perform(post("/api/orders/checkout")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentSummary").value("Cash"))
                .andExpect(jsonPath("$.totalAmount").value(39.98));

        // Real database check, not just the HTTP response.
        List<Order> persisted = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getTotalAmount()).isEqualByComparingTo(new BigDecimal("39.98"));
    }

    @Test
    void checkout_creditCardOverDeclineThreshold_orderNeverPersisted() throws Exception {
        Long userId = 200L;
        CartDTO.CartItemDTO expensiveItem = new CartDTO.CartItemDTO(2L, "Server Rack", new BigDecimal("1500.00"), 1);
        when(cartClient.getCart(userId)).thenReturn(new CartDTO(userId, List.of(expensiveItem)));
        when(productClient.decrementStock(eq(2L), eq(1), anyString())).thenReturn(new ProductDTO(2L, "Server Rack", new BigDecimal("1500.00"), 3));

        String body = "{\"paymentMethod\":\"CREDIT_CARD\",\"cardNumber\":\"4111111111111111\","
                + "\"cardHolderName\":\"Alice\",\"expiryMonth\":\"12\",\"expiryYear\":\"30\"}";

        mockMvc.perform(post("/api/orders/checkout")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(402)); // Payment Required

        // A declined payment must never leave a persisted order behind.
        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
    }

    @Test
    void listMyOrders_returnsOnlyThatUsersOrders() throws Exception {
        Long userId = 300L;
        CartDTO.CartItemDTO item = new CartDTO.CartItemDTO(3L, "Gadget", new BigDecimal("9.99"), 1);
        when(cartClient.getCart(userId)).thenReturn(new CartDTO(userId, List.of(item)));
        when(productClient.decrementStock(anyLong(), anyInt(), anyString()))
                .thenReturn(new ProductDTO(3L, "Gadget", new BigDecimal("9.99"), 99));

        mockMvc.perform(post("/api/orders/checkout")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/orders").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(userId));
    }
}
