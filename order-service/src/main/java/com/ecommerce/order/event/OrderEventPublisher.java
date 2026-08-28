package com.ecommerce.order.event;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// Pushes an already-built field map to Redis Streams unchanged. Trace headers are already
// embedded by OutboxEventWriter at write time. Exceptions propagate so OutboxEventPoller
// knows not to mark the row published.
@Component
public class OrderEventPublisher {

    /** Must match notification-service's RedisStreamConfig.STREAM_KEY exactly. */
    private static final String STREAM_KEY = "order-events";

    private final StringRedisTemplate redisTemplate;

    public OrderEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(Map<String, String> fields) {
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .ofStrings(fields)
                .withStreamKey(STREAM_KEY);

        org.springframework.data.redis.core.StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
        streamOps.add(record);
    }
}
