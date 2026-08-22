package com.ecommerce.order.event;

import com.ecommerce.order.entity.Order;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// Fire-and-forget publish - failure is only logged, never propagated, so Redis being down
// never fails an order. Redis Streams has no built-in trace propagation, so the span context
// is injected here and re-extracted in notification-service - see OrderEventTracing.
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

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

    public void publishOrderCreated(Order order) {
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("orderId", String.valueOf(order.getId()));
            fields.put("userId", String.valueOf(order.getUserId()));
            fields.put("totalAmount", order.getTotalAmount().toString());
            fields.put("itemCount", String.valueOf(order.getItems().size()));
            fields.put("createdAt", order.getCreatedAt().toString());
            injectTraceContext(fields);

            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .ofStrings(fields)
                    .withStreamKey(STREAM_KEY);

            org.springframework.data.redis.core.StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
            streamOps.add(record);
        } catch (Exception e) {
            log.warn("Failed to publish order-created event for order {} - the order itself was NOT affected, " +
                    "only its downstream notification will be delayed/missed", order.getId(), e);
        }
    }

    // No active span (e.g. called from a test or a scheduled job) just means the consumer
    // starts a fresh, unlinked trace - not an error case worth failing the publish over.
    private void injectTraceContext(Map<String, String> fields) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            propagator.inject(currentSpan.context(), fields, Map::put);
        }
    }
}
