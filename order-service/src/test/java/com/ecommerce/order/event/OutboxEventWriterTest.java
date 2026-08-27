package com.ecommerce.order.event;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OutboxEvent;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxEventWriterTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enqueueOrderCreated_writesRowWithSerializedFields() throws Exception {
        OutboxEventWriter writer = new OutboxEventWriter(outboxEventRepository, objectMapper);
        OrderItem item = OrderItem.builder().productId(10L).quantity(2).build();
        Order order = Order.builder()
                .id(42L)
                .userId(7L)
                .totalAmount(new BigDecimal("39.98"))
                .items(List.of(item))
                .createdAt(Instant.parse("2026-08-27T00:00:00Z"))
                .build();

        writer.enqueueOrderCreated(order);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();

        assertThat(saved.getOrderId()).isEqualTo(42L);
        assertThat(saved.getEventType()).isEqualTo("ORDER_CREATED");

        var fields = objectMapper.readValue(saved.getPayload(), java.util.Map.class);
        assertThat(fields.get("orderId")).isEqualTo("42");
        assertThat(fields.get("userId")).isEqualTo("7");
        assertThat(fields.get("totalAmount")).isEqualTo("39.98");
        assertThat(fields.get("itemCount")).isEqualTo("1");
    }
}
