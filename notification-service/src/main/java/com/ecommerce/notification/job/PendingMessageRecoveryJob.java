package com.ecommerce.notification.job;

import com.ecommerce.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.ecommerce.notification.config.RedisStreamConfig.CONSUMER_GROUP;
import static com.ecommerce.notification.config.RedisStreamConfig.CONSUMER_NAME;
import static com.ecommerce.notification.config.RedisStreamConfig.STREAM_KEY;

// Recovers messages delivered to this consumer but never acknowledged - most likely this
// service crashed mid-processing. Reading with ReadOffset.from("0") under the same consumer
// group + name returns that consumer's own pending backlog rather than new messages (standard
// XREADGROUP behavior). Since CONSUMER_NAME is fixed, this works even across a container restart.
@Component
public class PendingMessageRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingMessageRecoveryJob.class);

    private final StringRedisTemplate redisTemplate;
    private final NotificationService notificationService;

    public PendingMessageRecoveryJob(StringRedisTemplate redisTemplate, NotificationService notificationService) {
        this.redisTemplate = redisTemplate;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 20000)
    public void recoverOwnPendingMessages() {
        try {
            // opsForStream() is generic over HK/HV independent of StringRedisTemplate's own
            // types - assigning it to this explicitly-typed variable forces String inference
            // instead of chaining .read() directly off it, which defaults to Object.
            StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();

            List<MapRecord<String, String, String>> pending = streamOps.read(
                    Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                    StreamReadOptions.empty().count(50),
                    StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
            );

            if (pending == null || pending.isEmpty()) {
                return;
            }

            log.warn("Found {} unacknowledged order event(s) from a previous run - reprocessing", pending.size());

            for (MapRecord<String, String, String> record : pending) {
                try {
                    notificationService.handleOrderCreated(record.getValue(), record.getId().getValue());
                    streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
                } catch (Exception e) {
                    log.error("Retry failed for order event {} - will retry again next cycle", record.getId(), e);
                }
            }
        } catch (Exception e) {
            // Never let this optional recovery pass crash the service - the live listener
            // keeps handling new messages regardless.
            log.error("Pending message recovery check failed", e);
        }
    }
}
