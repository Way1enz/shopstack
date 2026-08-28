package com.ecommerce.notification.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Confirms RedisStreamConfig's consumer group receives and processes a record end to end.
@SpringBootTest(properties = "eureka.client.enabled=false")
@Testcontainers
class NotificationStreamIntegrationTest {

    private static final String PROCESSED_SET_KEY = "notifications:processed-order-ids";

    // Same image docker-compose.yml uses.
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void publishedOrderEvent_isConsumedAndMarkedProcessed() {
        Map<String, String> fields = new HashMap<>();
        fields.put("orderId", "999");
        fields.put("userId", "1");
        fields.put("totalAmount", "42.00");
        fields.put("itemCount", "1");
        fields.put("createdAt", "2026-08-27T00:00:00Z");

        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .ofStrings(fields)
                .withStreamKey("order-events");
        redisTemplate.opsForStream().add(record);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(redisTemplate.opsForSet().isMember(PROCESSED_SET_KEY, "999")).isTrue());
    }
}
