package com.ecommerce.order.event;

import com.ecommerce.order.client.resilient.ResilientProductClient;
import com.ecommerce.order.entity.CompensationTask;
import com.ecommerce.order.entity.CompensationTaskStatus;
import com.ecommerce.order.repository.CompensationTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompensationRetryJobTest {

    @Mock
    private CompensationTaskRepository compensationTaskRepository;

    @Mock
    private ResilientProductClient productClient;

    private CompensationTask task(int attempts) {
        return CompensationTask.builder()
                .id(1L).productId(10L).quantity(2).orderId(42L)
                .idempotencyKey("key-1").status(CompensationTaskStatus.PENDING).attempts(attempts)
                .build();
    }

    @Test
    void retry_restockSucceeds_marksResolved() {
        CompensationRetryJob job = new CompensationRetryJob(compensationTaskRepository, productClient, 5);
        CompensationTask task = task(0);
        when(compensationTaskRepository.findTop50ByStatusOrderByIdAsc(CompensationTaskStatus.PENDING))
                .thenReturn(List.of(task));

        CompensationRetryJob.RetryResult result = job.retry();

        assertThat(result).isEqualTo(new CompensationRetryJob.RetryResult(1, 1, 0, 0));
        assertThat(task.getStatus()).isEqualTo(CompensationTaskStatus.RESOLVED);
        assertThat(task.getResolvedAt()).isNotNull();
    }

    @Test
    void retry_restockFailsUnderMaxAttempts_incrementsAndStaysPending() {
        CompensationRetryJob job = new CompensationRetryJob(compensationTaskRepository, productClient, 5);
        CompensationTask task = task(1);
        when(compensationTaskRepository.findTop50ByStatusOrderByIdAsc(CompensationTaskStatus.PENDING))
                .thenReturn(List.of(task));
        doThrow(new RuntimeException("still unreachable")).when(productClient)
                .restock(anyLong(), anyInt(), anyString());

        CompensationRetryJob.RetryResult result = job.retry();

        assertThat(result).isEqualTo(new CompensationRetryJob.RetryResult(1, 0, 1, 0));
        assertThat(task.getStatus()).isEqualTo(CompensationTaskStatus.PENDING);
        assertThat(task.getAttempts()).isEqualTo(2);
    }

    @Test
    void retry_restockFailsAtMaxAttempts_movesToManualCorrection() {
        CompensationRetryJob job = new CompensationRetryJob(compensationTaskRepository, productClient, 5);
        CompensationTask task = task(4);
        when(compensationTaskRepository.findTop50ByStatusOrderByIdAsc(CompensationTaskStatus.PENDING))
                .thenReturn(List.of(task));
        doThrow(new RuntimeException("still unreachable")).when(productClient)
                .restock(anyLong(), anyInt(), anyString());

        CompensationRetryJob.RetryResult result = job.retry();

        assertThat(result).isEqualTo(new CompensationRetryJob.RetryResult(1, 0, 0, 1));
        assertThat(task.getStatus()).isEqualTo(CompensationTaskStatus.NEEDS_MANUAL_CORRECTION);
        assertThat(task.getAttempts()).isEqualTo(5);
    }
}
