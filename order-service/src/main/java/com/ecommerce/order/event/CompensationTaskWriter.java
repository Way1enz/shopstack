package com.ecommerce.order.event;

import com.ecommerce.order.entity.CompensationTask;
import com.ecommerce.order.repository.CompensationTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// REQUIRES_NEW: called from OrderService.restockOne()'s catch block, sometimes right before
// the caller's transaction rolls back (payment-decline path rethrows after this runs).
@Component
@RequiredArgsConstructor
public class CompensationTaskWriter {

    private final CompensationTaskRepository compensationTaskRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long productId, int quantity, Long orderId, String idempotencyKey) {
        compensationTaskRepository.save(CompensationTask.builder()
                .productId(productId)
                .quantity(quantity)
                .orderId(orderId)
                .idempotencyKey(idempotencyKey)
                .build());
    }
}
