package com.ecommerce.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String PROCESSED_SET_KEY = "notifications:processed-order-ids";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(redisTemplate);
    }

    @Test
    void handleOrderCreated_newOrder_marksProcessedAndSetsExpiry() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(PROCESSED_SET_KEY, "42")).thenReturn(false);

        notificationService.handleOrderCreated(Map.of(
                "orderId", "42",
                "userId", "7",
                "totalAmount", "19.99",
                "itemCount", "2"
        ), "1-0");

        verify(setOperations).add(PROCESSED_SET_KEY, "42");
        verify(redisTemplate).expire(eq(PROCESSED_SET_KEY), any(Duration.class));
    }

    @Test
    void handleOrderCreated_alreadyProcessed_skipsWithoutTouchingRedisAgain() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(PROCESSED_SET_KEY, "42")).thenReturn(true);

        notificationService.handleOrderCreated(Map.of(
                "orderId", "42",
                "userId", "7",
                "totalAmount", "19.99",
                "itemCount", "2"
        ), "1-0");

        verify(setOperations, never()).add(eq(PROCESSED_SET_KEY), any());
        verify(redisTemplate, never()).expire(eq(PROCESSED_SET_KEY), any(Duration.class));
    }
}
