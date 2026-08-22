package com.ecommerce.notification.job;

import com.ecommerce.notification.service.NotificationService;
import com.ecommerce.notification.tracing.OrderEventTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
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

// Recovers messages delivered but never acknowledged (likely a crash mid-processing).
// ReadOffset.from("0") under the same group+name returns this consumer's own pending
// backlog rather than new messages - standard XREADGROUP behavior.
@Component
public class PendingMessageRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingMessageRecoveryJob.class);

    private final StringRedisTemplate redisTemplate;
    private final NotificationService notificationService;
    private final Tracer tracer;
    private final Propagator propagator;

    public PendingMessageRecoveryJob(StringRedisTemplate redisTemplate, NotificationService notificationService,
                                      Tracer tracer, Propagator propagator) {
        this.redisTemplate = redisTemplate;
        this.notificationService = notificationService;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 20000)
    public void recoverOwnPendingMessages() {
        try {
            // Explicit type forces String inference instead of the Object default from chaining .read().
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
                    OrderEventTracing.process(tracer, propagator, record, true, () -> {
                        notificationService.handleOrderCreated(record.getValue(), record.getId().getValue());
                        streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
                    });
                } catch (Exception e) {
                    log.error("Retry failed for order event {} - will retry again next cycle", record.getId(), e);
                }
            }
        } catch (Exception e) {
            // Never let this optional recovery pass crash the service.
            log.error("Pending message recovery check failed", e);
        }
    }
}
