package com.ecommerce.order.event;

import com.ecommerce.order.entity.CompensationTask;
import com.ecommerce.order.repository.CompensationTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CompensationTaskWriterTest {

    @Mock
    private CompensationTaskRepository compensationTaskRepository;

    @Test
    void recordFailure_writesTaskWithGivenFields() {
        CompensationTaskWriter writer = new CompensationTaskWriter(compensationTaskRepository);

        writer.recordFailure(10L, 2, 42L, "key-123");

        ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
        verify(compensationTaskRepository).save(captor.capture());
        CompensationTask saved = captor.getValue();

        assertThat(saved.getProductId()).isEqualTo(10L);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getOrderId()).isEqualTo(42L);
        assertThat(saved.getIdempotencyKey()).isEqualTo("key-123");
    }

    @Test
    void recordFailure_nullOrderId_writesTaskWithNullOrderId() {
        CompensationTaskWriter writer = new CompensationTaskWriter(compensationTaskRepository);

        writer.recordFailure(10L, 2, null, "key-123");

        ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
        verify(compensationTaskRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderId()).isNull();
    }
}
