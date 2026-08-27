package com.ecommerce.notification.listener;

import com.ecommerce.notification.service.NotificationService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Tracer/Propagator are mocked with deep stubs since the span-builder chain isn't the behavior under test here.
@ExtendWith(MockitoExtension.class)
class OrderEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private Tracer tracer;
    private Propagator propagator;

    private OrderEventListener listener;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
        propagator = mock(Propagator.class, RETURNS_DEEP_STUBS);
        when(tracer.withSpan(any(Span.class))).thenReturn(() -> { });
        listener = new OrderEventListener(notificationService, redisTemplate, tracer, propagator);
    }

    @Test
    void onMessage_success_acknowledges() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        MapRecord<String, String, String> record = MapRecord.create("order-events",
                        Map.of("orderId", "42"))
                .withId(RecordId.of("1-0"));

        listener.onMessage(record);

        verify(notificationService).handleOrderCreated(record.getValue(), "1-0");
        verify(streamOperations).acknowledge("order-events", "notification-service-group", record.getId());
    }

    @Test
    void onMessage_handlerThrows_leavesUnacknowledged() {
        doThrow(new RuntimeException("boom")).when(notificationService)
                .handleOrderCreated(any(), any());
        MapRecord<String, String, String> record = MapRecord.create("order-events",
                        Map.of("orderId", "42"))
                .withId(RecordId.of("1-0"));

        listener.onMessage(record);

        verify(redisTemplate, never()).opsForStream();
    }
}
