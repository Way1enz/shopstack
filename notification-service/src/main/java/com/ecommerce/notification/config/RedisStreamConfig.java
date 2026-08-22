package com.ecommerce.notification.config;

import com.ecommerce.notification.listener.OrderEventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;

@Configuration
public class RedisStreamConfig {

    // Must match OrderEventPublisher.STREAM_KEY exactly.
    public static final String STREAM_KEY = "order-events";

    public static final String CONSUMER_GROUP = "notification-service-group";

    // Fixed, not random/host-based, so a crashed container can recover its own pending backlog
    // (see PendingMessageRecoveryJob). Multiple replicas would need unique names instead.
    public static final String CONSUMER_NAME = "notification-consumer-1";

    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> orderEventStreamContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            OrderEventListener orderEventListener) {

        ensureConsumerGroupExists(redisTemplate);

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        // Manual ack: onMessage() must call acknowledge() itself after processing succeeds.
        container.receive(
                Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                orderEventListener
        );

        container.start();
        return container;
    }

    // XGROUP CREATE ... MKSTREAM equivalent, run on startup. BUSYGROUP just means it already exists.
    private void ensureConsumerGroupExists(StringRedisTemplate redisTemplate) {
        try {
            org.springframework.data.redis.core.StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
            streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
        } catch (DataAccessException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    // RedisSystemException's own getMessage() is just "Error in execution". The real
    // "BUSYGROUP" text only lives on the cause, so this walks the chain instead of trusting
    // the top-level message.
    private boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
