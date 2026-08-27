package com.ecommerce.order.event;

import com.ecommerce.order.entity.OutboxEvent;
import com.ecommerce.order.entity.OutboxEventStatus;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// Double publish on a replica race is harmless: notification-service dedupes by orderId
// (see NotificationService.PROCESSED_SET_KEY).
@Component
@RequiredArgsConstructor
public class OutboxEventPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPoller.class);
    private static final TypeReference<Map<String, String>> FIELDS_TYPE = new TypeReference<>() { };

    private final OutboxEventRepository outboxEventRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final ObjectMapper objectMapper;

    public record PollResult(int found, int published, int failed) {
    }

    @Scheduled(fixedDelayString = "${order.outbox.poll-fixed-delay-ms:5000}",
            initialDelayString = "${order.outbox.poll-initial-delay-ms:10000}")
    public void scheduledPoll() {
        poll();
    }

    @Transactional
    public PollResult poll() {
        List<OutboxEvent> pending = outboxEventRepository.findTop50ByStatusOrderByIdAsc(OutboxEventStatus.PENDING);
        int published = 0;
        int failed = 0;

        for (OutboxEvent event : pending) {
            try {
                Map<String, String> fields = objectMapper.readValue(event.getPayload(), FIELDS_TYPE);
                orderEventPublisher.publish(fields);
                event.setStatus(OutboxEventStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
                published++;
            } catch (Exception e) {
                event.setAttempts(event.getAttempts() + 1);
                outboxEventRepository.save(event);
                log.warn("Failed to publish outbox event {} for order {} (attempt {}) - will retry next cycle",
                        event.getId(), event.getOrderId(), event.getAttempts(), e);
                failed++;
            }
        }

        return new PollResult(pending.size(), published, failed);
    }
}
