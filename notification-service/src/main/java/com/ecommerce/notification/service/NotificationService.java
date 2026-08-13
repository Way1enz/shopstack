package com.ecommerce.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String PROCESSED_SET_KEY = "notifications:processed-order-ids";

    private final StringRedisTemplate redisTemplate;

    public NotificationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // Simulated send (swap the log line for a real email/SMS integration later). Idempotency
    // check matters because Redis Streams delivery is at-least-once - without it a redelivered
    // message would send a duplicate confirmation.
    public void handleOrderCreated(Map<String, String> fields, String recordId) {
        String orderId = fields.get("orderId");

        Boolean alreadyProcessed = redisTemplate.opsForSet().isMember(PROCESSED_SET_KEY, orderId);
        if (Boolean.TRUE.equals(alreadyProcessed)) {
            log.info("Order {} already processed (record {}) - skipping duplicate delivery", orderId, recordId);
            return;
        }

        String userId = fields.get("userId");
        String totalAmount = fields.get("totalAmount");
        String itemCount = fields.get("itemCount");

        log.info("Order confirmation -> user {} | order {} | {} item(s) | total ${}",
                userId, orderId, itemCount, totalAmount);

        redisTemplate.opsForSet().add(PROCESSED_SET_KEY, orderId);
        redisTemplate.expire(PROCESSED_SET_KEY, Duration.ofDays(7));
    }
}
