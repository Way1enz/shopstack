package com.ecommerce.notification.listener;

import com.ecommerce.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import static com.ecommerce.notification.config.RedisStreamConfig.CONSUMER_GROUP;
import static com.ecommerce.notification.config.RedisStreamConfig.STREAM_KEY;

// Only acknowledges after handleOrderCreated() succeeds - if it throws, the message stays
// unacknowledged and PendingMessageRecoveryJob picks it up later.
@Component
public class OrderEventListener implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;

    public OrderEventListener(NotificationService notificationService, StringRedisTemplate redisTemplate) {
        this.notificationService = notificationService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        try {
            notificationService.handleOrderCreated(record.getValue(), record.getId().getValue());
            org.springframework.data.redis.core.StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
        } catch (Exception e) {
            log.error("Failed to process order event {} - leaving unacknowledged for retry", record.getId(), e);
        }
    }
}
