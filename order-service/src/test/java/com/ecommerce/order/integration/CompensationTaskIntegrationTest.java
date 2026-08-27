package com.ecommerce.order.integration;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.CartDTO;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.client.ProductDTO;
import com.ecommerce.order.entity.CompensationTaskStatus;
import com.ecommerce.order.event.CompensationRetryJob;
import com.ecommerce.order.repository.CompensationTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Postgres: confirms the full decline -> queue -> retry -> resolve loop.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"eureka.client.enabled=false", "order.outbox.poll-initial-delay-ms=600000",
                "order.compensation.retry-initial-delay-ms=600000"})
@AutoConfigureMockMvc
@Testcontainers
class CompensationTaskIntegrationTest {

    // Same image docker-compose.yml uses.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CompensationTaskRepository compensationTaskRepository;

    @Autowired
    private CompensationRetryJob compensationRetryJob;

    @MockitoBean
    private CartClient cartClient;

    @MockitoBean
    private ProductClient productClient;

    @Test
    void paymentDeclineWithFailingRestock_queuesTaskThenResolvesOnRetry() throws Exception {
        Long userId = 500L;
        CartDTO.CartItemDTO expensiveItem = new CartDTO.CartItemDTO(1L, "Server Rack", new BigDecimal("1500.00"), 1);
        when(cartClient.getCart(userId)).thenReturn(new CartDTO(userId, List.of(expensiveItem)));
        when(productClient.decrementStock(eq(1L), eq(1), anyString()))
                .thenReturn(new ProductDTO(1L, "Server Rack", new BigDecimal("1500.00"), 3));
        doThrow(new RuntimeException("product-service unreachable")).when(productClient)
                .restock(anyLong(), anyInt(), anyString());

        String body = "{\"paymentMethod\":\"CREDIT_CARD\",\"cardNumber\":\"4111111111111111\","
                + "\"cardHolderName\":\"Alice\",\"expiryMonth\":\"12\",\"expiryYear\":\"30\"}";

        mockMvc.perform(post("/api/orders/checkout")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(402)); // Payment Required

        assertThat(compensationTaskRepository.findTop50ByStatusOrderByIdAsc(CompensationTaskStatus.PENDING))
                .anyMatch(t -> t.getProductId().equals(1L));

        // product-service recovers.
        org.mockito.Mockito.reset(productClient);
        when(productClient.restock(anyLong(), anyInt(), anyString()))
                .thenReturn(new ProductDTO(1L, "Server Rack", new BigDecimal("1500.00"), 4));

        CompensationRetryJob.RetryResult result = compensationRetryJob.retry();

        assertThat(result.resolved()).isGreaterThanOrEqualTo(1);
        assertThat(compensationTaskRepository.findTop50ByStatusOrderByIdAsc(CompensationTaskStatus.PENDING))
                .noneMatch(t -> t.getProductId().equals(1L));
    }
}
