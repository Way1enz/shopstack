package com.ecommerce.order.actuator;

import com.ecommerce.order.entity.CompensationTask;
import com.ecommerce.order.event.CompensationRetryJob;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.List;

// GET /actuator/compensationRecovery lists tasks stuck in NEEDS_MANUAL_CORRECTION.
// POST /actuator/compensationRecovery runs the same scan CompensationRetryJob's
// @Scheduled cadence does, on demand. Internal only, not routed through the gateway.
@Component
@Endpoint(id = "compensationRecovery")
public class CompensationRecoveryEndpoint {

    private final CompensationRetryJob retryJob;

    public CompensationRecoveryEndpoint(CompensationRetryJob retryJob) {
        this.retryJob = retryJob;
    }

    @ReadOperation
    public List<CompensationTask> stuckTasks() {
        return retryJob.listNeedingManualCorrection();
    }

    @WriteOperation
    public CompensationRetryJob.RetryResult trigger() {
        return retryJob.retry();
    }
}
