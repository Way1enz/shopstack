package com.ecommerce.order.integration;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.CartDTO;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.client.ProductDTO;
import com.ecommerce.order.entity.OutboxEventStatus;
import com.ecommerce.order.event.OutboxEventPoller;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Postgres + Redis: confirms an outbox row reaches the Redis stream.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"eureka.client.enabled=false", "order.outbox.poll-initial-delay-ms=600000"})
@AutoConfigureMockMvc
@Testcontainers
class OutboxPollerIntegrationTest {

    // Same image docker-compose.yml uses.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    // No @ServiceConnection support for Redis yet, so wired manually via @DynamicPropertySource.
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxEventPoller outboxEventPoller;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private CartClient cartClient;

    @MockitoBean
    private ProductClient productClient;

    @Test
    void checkoutThenPoll_publishesOrderEventToRedisStream() throws Exception {
        Long userId = 400L;
        CartDTO.CartItemDTO item = new CartDTO.CartItemDTO(1L, "Widget", new BigDecimal("19.99"), 2);
        when(cartClient.getCart(userId)).thenReturn(new CartDTO(userId, List.of(item)));
        when(productClient.decrementStock(eq(1L), eq(2), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ProductDTO(1L, "Widget", new BigDecimal("19.99"), 8));

        mockMvc.perform(post("/api/orders/checkout")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"123 Main St\",\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isCreated());

        Long orderId = orderRepository.findByUserIdOrderByCreatedAtDesc(userId).get(0).getId();
        assertThat(outboxEventRepository.findTop50ByStatusOrderByIdAsc(OutboxEventStatus.PENDING)).hasSize(1);

        OutboxEventPoller.PollResult result = outboxEventPoller.poll();
        assertThat(result.published()).isEqualTo(1);
        assertThat(outboxEventRepository.findTop50ByStatusOrderByIdAsc(OutboxEventStatus.PENDING)).isEmpty();

        org.springframework.data.redis.core.StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
        List<MapRecord<String, Object, Object>> records = streamOps.read(
                org.springframework.data.redis.connection.stream.StreamOffset.fromStart("order-events"));
        MapRecord<String, Object, Object> record = records.stream()
                .filter(r -> String.valueOf(orderId).equals(r.getValue().get("orderId")))
                .findFirst()
                .orElseThrow();

        // Trace context comes from the checkout request's span, captured in OutboxEventWriter
        // at write time, not from OutboxEventPoller's scheduled thread.
        assertThat(record.getValue()).containsKey("traceparent");
    }
}
