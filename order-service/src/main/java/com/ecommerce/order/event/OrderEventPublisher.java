package com.ecommerce.order.event;

import com.ecommerce.order.entity.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// Publishes to the "order-events" stream after checkout succeeds - notification-service consumes
// it asynchronously. Deliberately fire-and-forget: called after the order is already committed,
// and any failure here is just logged, never propagated. A successful order should never fail
// because Redis happened to be unreachable at that moment.
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    /** Must match notification-service's RedisStreamConfig.STREAM_KEY exactly. */
    private static final String STREAM_KEY = "order-events";

    private final StringRedisTemplate redisTemplate;

    public OrderEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publishOrderCreated(Order order) {
        try {
            Map<String, String> fields = Map.of(
                    "orderId", String.valueOf(order.getId()),
                    "userId", String.valueOf(order.getUserId()),
                    "totalAmount", order.getTotalAmount().toString(),
                    "itemCount", String.valueOf(order.getItems().size()),
                    "createdAt", order.getCreatedAt().toString()
            );

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
}
