package com.ecommerce.product.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        guard = new IdempotencyGuard(redisTemplate);
    }

    @Test
    void claim_firstTime_returnsTrueAndUsesNamespacedKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idem:decrement-stock:1:key-1"), anyString(), eq(Duration.ofHours(24))))
                .thenReturn(true);

        boolean result = guard.claim("decrement-stock:1", "key-1");

        assertThat(result).isTrue();
    }

    @Test
    void claim_duplicate_returnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        boolean result = guard.claim("decrement-stock:1", "key-1");

        assertThat(result).isFalse();
    }

    @Test
    void claim_nullFromRedis_returnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        boolean result = guard.claim("decrement-stock:1", "key-1");

        assertThat(result).isFalse();
    }

    @Test
    void release_deletesNamespacedKey() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        guard.release("restock:1", "key-2");

        verify(redisTemplate).delete(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("idem:restock:1:key-2");
    }
}
