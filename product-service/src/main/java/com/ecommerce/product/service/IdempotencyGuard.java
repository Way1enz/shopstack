package com.ecommerce.product.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

// Backs decrementStock/restock against a retried call that succeeded but whose response
// was lost: claim() is an atomic Redis SETNX per Idempotency-Key, so the first caller
// proceeds and every retry after it is told it's a duplicate.
@Component
public class IdempotencyGuard {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public IdempotencyGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Attempts to claim {@code idempotencyKey} for {@code operation}. Returns true if this is
     * the first time this key has been seen (caller should perform the mutation), false if it's
     * a duplicate (caller should skip the mutation and just return the current state).
     */
    public boolean claim(String operation, String idempotencyKey) {
        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent(redisKey(operation, idempotencyKey), Instant.now().toString(), TTL);
        return Boolean.TRUE.equals(claimed);
    }

    /** Releases a claim so a legitimate retry isn't permanently blocked by a failed attempt. */
    public void release(String operation, String idempotencyKey) {
        redisTemplate.delete(redisKey(operation, idempotencyKey));
    }

    private String redisKey(String operation, String idempotencyKey) {
        return "idem:" + operation + ":" + idempotencyKey;
    }
}
