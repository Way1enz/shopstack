package com.ecommerce.notification.job;

import com.ecommerce.notification.service.NotificationService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Tracer/Propagator are mocked with deep stubs since the span-builder chain isn't the behavior under test here.
@ExtendWith(MockitoExtension.class)
class PendingMessageRecoveryJobTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private NotificationService notificationService;

    @Mock
    private StreamOperations<String, String, String> streamOperations;

    private Tracer tracer;
    private Propagator propagator;

    private PendingMessageRecoveryJob job;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
        propagator = mock(Propagator.class, RETURNS_DEEP_STUBS);
        when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
        job = new PendingMessageRecoveryJob(redisTemplate, notificationService, tracer, propagator);
    }

    // Only stubbed where a record is actually processed, so strict-stub tests that
    // return early (empty/null/read-throws) don't fail on an unused stub.
    private void stubTracerSpanScope() {
        when(tracer.withSpan(any(Span.class))).thenReturn(() -> { });
    }

    private MapRecord<String, String, String> record(String id, String orderId) {
        return MapRecord.create("order-events", Map.of("orderId", orderId)).withId(RecordId.of(id));
    }

    // Untyped any() cannot pick between StreamOperations' two overloaded read() methods; the
    // typed matchers here resolve to the (Consumer, StreamReadOptions, StreamOffset...) overload.
    private org.mockito.stubbing.OngoingStubbing<List<MapRecord<String, String, String>>> stubRead() {
        return when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)));
    }

    @Test
    void runRecoveryScan_noPendingMessages_returnsAllZero() {
        stubRead().thenReturn(List.of());

        PendingMessageRecoveryJob.RecoveryResult result = job.runRecoveryScan();

        assertThat(result).isEqualTo(new PendingMessageRecoveryJob.RecoveryResult(0, 0, 0));
        verify(notificationService, never()).handleOrderCreated(any(), any());
    }

    @Test
    void runRecoveryScan_nullReadResult_returnsAllZero() {
        stubRead().thenReturn(null);

        PendingMessageRecoveryJob.RecoveryResult result = job.runRecoveryScan();

        assertThat(result).isEqualTo(new PendingMessageRecoveryJob.RecoveryResult(0, 0, 0));
    }

    @Test
    void runRecoveryScan_allSucceed_acknowledgesEach() {
        stubTracerSpanScope();
        MapRecord<String, String, String> r1 = record("1-0", "42");
        MapRecord<String, String, String> r2 = record("2-0", "43");
        stubRead().thenReturn(List.of(r1, r2));

        PendingMessageRecoveryJob.RecoveryResult result = job.runRecoveryScan();

        assertThat(result).isEqualTo(new PendingMessageRecoveryJob.RecoveryResult(2, 2, 0));
        verify(notificationService).handleOrderCreated(r1.getValue(), "1-0");
        verify(notificationService).handleOrderCreated(r2.getValue(), "2-0");
        verify(streamOperations).acknowledge("order-events", "notification-service-group", r1.getId());
        verify(streamOperations).acknowledge("order-events", "notification-service-group", r2.getId());
    }

    @Test
    void runRecoveryScan_oneFails_countsFailureAndLeavesUnacknowledged() {
        stubTracerSpanScope();
        MapRecord<String, String, String> r1 = record("1-0", "42");
        MapRecord<String, String, String> r2 = record("2-0", "43");
        stubRead().thenReturn(List.of(r1, r2));
        doThrow(new RuntimeException("boom")).when(notificationService).handleOrderCreated(r1.getValue(), "1-0");

        PendingMessageRecoveryJob.RecoveryResult result = job.runRecoveryScan();

        assertThat(result).isEqualTo(new PendingMessageRecoveryJob.RecoveryResult(2, 1, 1));
        verify(streamOperations, never()).acknowledge("order-events", "notification-service-group", r1.getId());
        verify(streamOperations).acknowledge("order-events", "notification-service-group", r2.getId());
    }

    @Test
    void runRecoveryScan_readThrows_returnsAllZeroWithoutPropagating() {
        stubRead().thenThrow(new RuntimeException("redis down"));

        PendingMessageRecoveryJob.RecoveryResult result = job.runRecoveryScan();

        assertThat(result).isEqualTo(new PendingMessageRecoveryJob.RecoveryResult(0, 0, 0));
    }

    @Test
    void scheduledRecovery_delegatesToRunRecoveryScan() {
        stubRead().thenReturn(List.of());

        job.scheduledRecovery();

        verify(streamOperations).read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));
    }
}
