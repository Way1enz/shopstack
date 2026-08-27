package com.ecommerce.order.event;

import com.ecommerce.order.entity.OutboxEvent;
import com.ecommerce.order.entity.OutboxEventStatus;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventPollerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxEventPoller poller() {
        return new OutboxEventPoller(outboxEventRepository, orderEventPublisher, objectMapper);
    }

    private OutboxEvent pendingEvent(Long id) throws Exception {
        return OutboxEvent.builder()
                .id(id)
                .orderId(id)
                .eventType("ORDER_CREATED")
                .payload(objectMapper.writeValueAsString(Map.of("orderId", String.valueOf(id))))
                .status(OutboxEventStatus.PENDING)
                .attempts(0)
                .build();
    }

    @Test
    void poll_publishSucceeds_marksPublished() throws Exception {
        OutboxEvent event = pendingEvent(1L);
        when(outboxEventRepository.findTop50ByStatusOrderByIdAsc(OutboxEventStatus.PENDING))
                .thenReturn(List.of(event));

        OutboxEventPoller.PollResult result = poller().poll();

        assertThat(result).isEqualTo(new OutboxEventPoller.PollResult(1, 1, 0));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void poll_publishFails_incrementsAttemptsAndLeavesPending() throws Exception {
        OutboxEvent event = pendingEvent(2L);
        when(outboxEventRepository.findTop50ByStatusOrderByIdAsc(OutboxEventStatus.PENDING))
                .thenReturn(List.of(event));
        doThrow(new RuntimeException("redis unreachable")).when(orderEventPublisher).publish(any());

        OutboxEventPoller.PollResult result = poller().poll();

        assertThat(result).isEqualTo(new OutboxEventPoller.PollResult(1, 0, 1));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
    }
}
