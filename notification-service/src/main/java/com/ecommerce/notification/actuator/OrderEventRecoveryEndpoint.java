package com.ecommerce.notification.actuator;

import com.ecommerce.notification.job.PendingMessageRecoveryJob;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

// Manual trigger for PendingMessageRecoveryJob - same scan as the @Scheduled cadence, on demand.
// POST /actuator/orderEventRecovery. Internal only, not routed through the gateway.
@Component
@Endpoint(id = "orderEventRecovery")
public class OrderEventRecoveryEndpoint {

    private final PendingMessageRecoveryJob recoveryJob;

    public OrderEventRecoveryEndpoint(PendingMessageRecoveryJob recoveryJob) {
        this.recoveryJob = recoveryJob;
    }

    @WriteOperation
    public PendingMessageRecoveryJob.RecoveryResult trigger() {
        return recoveryJob.runRecoveryScan();
    }
}
