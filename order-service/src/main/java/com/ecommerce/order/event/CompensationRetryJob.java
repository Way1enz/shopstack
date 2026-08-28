package com.ecommerce.order.event;

import com.ecommerce.order.client.resilient.ResilientProductClient;
import com.ecommerce.order.entity.CompensationTask;
import com.ecommerce.order.entity.CompensationTaskStatus;
import com.ecommerce.order.repository.CompensationTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class CompensationRetryJob {

    private static final Logger log = LoggerFactory.getLogger(CompensationRetryJob.class);

    private final CompensationTaskRepository compensationTaskRepository;
    private final ResilientProductClient productClient;
    private final int maxAttempts;

    public CompensationRetryJob(CompensationTaskRepository compensationTaskRepository,
                                 ResilientProductClient productClient,
                                 @Value("${order.compensation.max-attempts:5}") int maxAttempts) {
        this.compensationTaskRepository = compensationTaskRepository;
        this.productClient = productClient;
        this.maxAttempts = maxAttempts;
    }

    public record RetryResult(int found, int resolved, int failed, int movedToManualCorrection) {
    }

    @Scheduled(fixedDelayString = "${order.compensation.retry-fixed-delay-ms:5000}",
            initialDelayString = "${order.compensation.retry-initial-delay-ms:15000}")
    public void scheduledRetry() {
        retry();
    }

    @Transactional
    public RetryResult retry() {
        List<CompensationTask> pending = compensationTaskRepository.findTop50ByStatusOrderByIdAsc(CompensationTaskStatus.PENDING);
        int resolved = 0;
        int failed = 0;
        int movedToManualCorrection = 0;

        for (CompensationTask task : pending) {
            try {
                productClient.restock(task.getProductId(), task.getQuantity(), task.getIdempotencyKey());
                task.setStatus(CompensationTaskStatus.RESOLVED);
                task.setResolvedAt(Instant.now());
                compensationTaskRepository.save(task);
                resolved++;
            } catch (Exception e) {
                task.setAttempts(task.getAttempts() + 1);
                if (task.getAttempts() >= maxAttempts) {
                    task.setStatus(CompensationTaskStatus.NEEDS_MANUAL_CORRECTION);
                    log.error("Compensation task {} for product {} (qty {}) exhausted {} attempts - needs manual correction",
                            task.getId(), task.getProductId(), task.getQuantity(), maxAttempts, e);
                    movedToManualCorrection++;
                } else {
                    log.warn("Compensation retry failed for product {} (qty {}, attempt {}/{}) - will retry next cycle",
                            task.getProductId(), task.getQuantity(), task.getAttempts(), maxAttempts, e);
                    failed++;
                }
                compensationTaskRepository.save(task);
            }
        }

        return new RetryResult(pending.size(), resolved, failed, movedToManualCorrection);
    }

    public List<CompensationTask> listNeedingManualCorrection() {
        return compensationTaskRepository.findByStatusOrderByIdAsc(CompensationTaskStatus.NEEDS_MANUAL_CORRECTION);
    }
}
