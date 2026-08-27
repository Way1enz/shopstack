package com.ecommerce.order.event;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// Pushes an already-built field map to Redis Streams. Called by OutboxEventPoller, which
// needs the exception to propagate on failure so it knows not to mark the row published.
@Component
public class OrderEventPublisher {

    /** Must match notification-service's RedisStreamConfig.STREAM_KEY exactly. */
    private static final String STREAM_KEY = "order-events";

    private final StringRedisTemplate redisTemplate;
    private final Tracer tracer;
    private final Propagator propagator;

    public OrderEventPublisher(StringRedisTemplate redisTemplate, Tracer tracer, Propagator propagator) {
        this.redisTemplate = redisTemplate;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void publish(Map<String, String> fields) {
        injectTraceContext(fields);

        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .ofStrings(fields)
                .withStreamKey(STREAM_KEY);

        org.springframework.data.redis.core.StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
        streamOps.add(record);
    }

    // No active span (e.g. called from OutboxEventPoller's scheduled thread) just means the
    // consumer starts a fresh, unlinked trace instead of failing the publish.
    private void injectTraceContext(Map<String, String> fields) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            propagator.inject(currentSpan.context(), fields, Map::put);
        }
    }
}
