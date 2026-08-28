package com.ecommerce.order.event;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OutboxEvent;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// Runs inside the same @Transactional method as the order save, so the row commits or
// rolls back together with the order. Trace context is captured here, at write time, while
// the checkout request's span is still active.
@Component
@RequiredArgsConstructor
public class OutboxEventWriter {

    private static final String ORDER_CREATED = "ORDER_CREATED";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    public void enqueueOrderCreated(Order order) {
        Map<String, String> fields = new HashMap<>();
        fields.put("orderId", String.valueOf(order.getId()));
        fields.put("userId", String.valueOf(order.getUserId()));
        fields.put("totalAmount", order.getTotalAmount().toString());
        fields.put("itemCount", String.valueOf(order.getItems().size()));
        fields.put("createdAt", order.getCreatedAt().toString());
        injectTraceContext(fields);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload for order " + order.getId(), e);
        }

        outboxEventRepository.save(OutboxEvent.builder()
                .orderId(order.getId())
                .eventType(ORDER_CREATED)
                .payload(payload)
                .build());
    }

    // No active span (unusual for an HTTP request thread, but not impossible) just means the
    // consumer starts a fresh, unlinked trace instead of failing the write.
    private void injectTraceContext(Map<String, String> fields) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            propagator.inject(currentSpan.context(), fields, Map::put);
        }
    }
}
