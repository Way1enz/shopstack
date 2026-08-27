package com.ecommerce.order.event;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OutboxEvent;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventWriterTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Order sampleOrder() {
        OrderItem item = OrderItem.builder().productId(10L).quantity(2).build();
        return Order.builder()
                .id(42L)
                .userId(7L)
                .totalAmount(new BigDecimal("39.98"))
                .items(List.of(item))
                .createdAt(Instant.parse("2026-08-27T00:00:00Z"))
                .build();
    }

    @Test
    void enqueueOrderCreated_writesRowWithSerializedFields() throws Exception {
        OutboxEventWriter writer = new OutboxEventWriter(outboxEventRepository, objectMapper, tracer, propagator);

        writer.enqueueOrderCreated(sampleOrder());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();

        assertThat(saved.getOrderId()).isEqualTo(42L);
        assertThat(saved.getEventType()).isEqualTo("ORDER_CREATED");

        var fields = objectMapper.readValue(saved.getPayload(), Map.class);
        assertThat(fields.get("orderId")).isEqualTo("42");
        assertThat(fields.get("userId")).isEqualTo("7");
        assertThat(fields.get("totalAmount")).isEqualTo("39.98");
        assertThat(fields.get("itemCount")).isEqualTo("1");
    }

    // Trace context must be captured at write time, on the checkout request's own thread,
    // not later when OutboxEventPoller publishes on a @Scheduled thread with no active span.
    @Test
    void enqueueOrderCreated_activeSpan_injectsTraceContextIntoPayload() {
        OutboxEventWriter writer = new OutboxEventWriter(outboxEventRepository, objectMapper, tracer, propagator);
        Span span = org.mockito.Mockito.mock(Span.class);
        TraceContext context = org.mockito.Mockito.mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);

        writer.enqueueOrderCreated(sampleOrder());

        verify(propagator).inject(eq(context), any(), any());
    }
}
