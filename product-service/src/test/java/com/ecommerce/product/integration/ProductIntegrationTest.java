package com.ecommerce.product.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Postgres + Redis - exercises the @Cacheable/@CacheEvict path with actual JSON serialization.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class ProductIntegrationTest {

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
    private ObjectMapper objectMapper;

    private String productJson(String name, String price, int stock) {
        return "{\"name\":\"" + name + "\",\"description\":\"test\",\"price\":" + price
                + ",\"stockQuantity\":" + stock + ",\"category\":\"Electronics\"}";
    }

    @Test
    void create_thenGetById_populatesCacheSuccessfully() throws Exception {
        String created = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Keyboard", "89.99", 25)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Keyboard"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(created).get("id").asLong();

        // Cache miss: fetches from Postgres, serializes into Redis (Instant fields need JavaTimeModule).
        mockMvc.perform(get("/api/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keyboard"));

        // Cache hit: deserializes from Redis.
        mockMvc.perform(get("/api/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keyboard"));
    }

    @Test
    void update_evictsCacheSoSubsequentGetReflectsNewData() throws Exception {
        String created = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Mouse", "29.99", 50)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(created).get("id").asLong();

        // Populate cache with original price.
        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isOk());

        mockMvc.perform(put("/api/products/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Mouse", "19.99", 50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(19.99));

        // Confirms eviction worked - would otherwise return the stale 29.99 price.
        mockMvc.perform(get("/api/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(19.99));
    }

    @Test
    void delete_thenGetById_returns404() throws Exception {
        String created = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Monitor", "199.99", 10)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/products/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void create_invalidPrice_returns400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("BadProduct", "-5.00", 10)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_withCategoryFilter_returns200() throws Exception {
        // Containers are shared across tests; data accumulates, so this only smoke-tests the endpoint.
        mockMvc.perform(post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson("Desk Lamp", "24.99", 15)));

        mockMvc.perform(get("/api/products").param("category", "Electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}
