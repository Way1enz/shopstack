package com.ecommerce.product.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

// Real Postgres AND real Redis - this specifically exercises the @Cacheable/@CacheEvict path
// with actual JSON serialization to Redis, which is exactly where two real bugs (Jackson not
// handling java.time.Instant) were caught earlier via manual testing. This test would have
// caught both of those the moment it existed, instead of needing a live Bruno session to
// surface them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class ProductIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // Redis isn't a first-class Testcontainers module the way Postgres is, so this is wired
    // manually via @DynamicPropertySource rather than @ServiceConnection - more verbose, but
    // the more broadly reliable mechanism.
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

        // First GET is a cache miss - fetches from Postgres, then serializes the whole Product
        // (including its Instant createdAt/updatedAt fields) into Redis. This exact write is
        // what threw "Java 8 date/time type Instant not supported" before JavaTimeModule was
        // registered on the cache's ObjectMapper.
        mockMvc.perform(get("/api/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keyboard"));

        // Second GET is a cache hit - deserializes the same Instant fields back out of Redis.
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

        // Populate the cache with the original price.
        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isOk());

        mockMvc.perform(put("/api/products/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Mouse", "19.99", 50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(19.99));

        // If cache eviction on update didn't work, this would incorrectly return the stale
        // 29.99 price from before the update instead of hitting Postgres again.
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
        // Containers are shared (static) across every test method in this class for speed, so
        // Postgres data accumulates across tests rather than resetting between them, and JUnit
        // doesn't guarantee method execution order by default. A strict "exactly one result"
        // assertion here would be flaky depending on what other tests already ran - this stays
        // a light smoke test on the endpoint responding correctly, not on exclusivity.
        mockMvc.perform(post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson("Desk Lamp", "24.99", 15)));

        mockMvc.perform(get("/api/products").param("category", "Electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}
