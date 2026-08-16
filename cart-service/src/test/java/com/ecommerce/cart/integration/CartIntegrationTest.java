package com.ecommerce.cart.integration;

import com.ecommerce.cart.client.ProductClient;
import com.ecommerce.cart.client.ProductDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Redis - this is the actual database for cart-service, not a cache sitting in front of
// one, so there's no meaningful integration test here without it. ProductClient is mocked
// since product-service gets its own integration test elsewhere.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class CartIntegrationTest {

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

    @MockBean
    private ProductClient productClient;

    @Test
    void addItem_thenGetCart_itemPersistedInRealRedis() throws Exception {
        Long userId = 1L;
        when(productClient.getProduct(10L)).thenReturn(new ProductDTO(10L, "Keyboard", new BigDecimal("89.99"), 25));

        mockMvc.perform(post("/api/cart/items")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":10,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(10))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        // Separate GET, not just trusting the POST response - proves the write actually
        // landed in Redis and can be read back, not just held in memory for this one request.
        mockMvc.perform(get("/api/cart").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("Keyboard"));
    }

    @Test
    void addItem_sameProductTwice_mergesQuantityInsteadOfDuplicating() throws Exception {
        Long userId = 2L;
        when(productClient.getProduct(20L)).thenReturn(new ProductDTO(20L, "Mouse", new BigDecimal("29.99"), 50));

        mockMvc.perform(post("/api/cart/items")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":20,\"quantity\":1}"));

        mockMvc.perform(post("/api/cart/items")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":20,\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(4));
    }

    @Test
    void removeItem_deletesFromRealRedis() throws Exception {
        Long userId = 3L;
        when(productClient.getProduct(30L)).thenReturn(new ProductDTO(30L, "Monitor", new BigDecimal("199.99"), 10));

        mockMvc.perform(post("/api/cart/items")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":30,\"quantity\":1}"));

        mockMvc.perform(delete("/api/cart/items/30").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void clearCart_removesAllItems() throws Exception {
        Long userId = 4L;
        when(productClient.getProduct(40L)).thenReturn(new ProductDTO(40L, "Webcam", new BigDecimal("49.99"), 20));

        mockMvc.perform(post("/api/cart/items")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":40,\"quantity\":1}"));

        mockMvc.perform(delete("/api/cart").header("X-User-Id", userId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/cart").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void getCart_neverUsed_returnsEmptyCartNot404() throws Exception {
        mockMvc.perform(get("/api/cart").header("X-User-Id", 999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
