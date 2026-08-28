package com.ecommerce.notification.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.util.Map;

import static com.ecommerce.notification.config.RedisStreamConfig.STREAM_KEY;

// Shared by OrderEventListener and PendingMessageRecoveryJob: both extract the traceparent
// field OrderEventPublisher injects and continue as a child span, even on redelivery.
public final class OrderEventTracing {

    private OrderEventTracing() {
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void process(Tracer tracer, Propagator propagator, MapRecord<String, String, String> record,
                         boolean redelivered, ThrowingRunnable action) throws Exception {
        Span span = propagator.extract(record.getValue(), Map::get)
                .name("order-events receive")
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.system", "redis-streams")
                .tag("messaging.destination", STREAM_KEY)
                .tag("messaging.redelivered", String.valueOf(redelivered))
                .start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            action.run();
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
