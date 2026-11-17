package dev.difme.core.cmd.app;

import dev.difme.core.cmd.ledger.LedgerClient;
import dev.difme.core.common.StepIds;
import dev.difme.core.common.saga.PaymentWorkflow;
import dev.difme.core.common.saga.TransferInput;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * Outbound side effects of a payment, each safe to retry because it is idempotent by construction (ADR-017).
 * Kept in their own bean so the fault-tolerance interceptors apply (self-invocation would bypass them).
 */
@ApplicationScoped
public class GuardedCalls {
    @Inject WorkflowClient temporal;
    @Inject LedgerClient ledger;

    /**
     * Starts the saga. Workflow id = payment id and REJECT_DUPLICATE: a retry or recovery sweep can never start a
     * second run of a payment, even after the first run has finished. "Already started" counts as success.
     */
    @Timeout(3000)
    @Retry(maxRetries = 3, delay = 100, jitter = 100, abortOn = IllegalArgumentException.class)
    @CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5, delay = 10000, successThreshold = 3)
    public void startSaga(TransferInput in) {
        PaymentWorkflow wf = temporal.newWorkflowStub(PaymentWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(in.paymentId())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setTaskQueue(PaymentWorkflow.TASK_QUEUE)
                .build());
        try {
            WorkflowClient.start(wf::run, in);
        } catch (WorkflowExecutionAlreadyStarted alreadyRunningOrDone) {
            // idempotent: the payment is already being (or has been) processed
        }
    }

    /**
     * Same-bank direct transfer. The transfer id is derived from the payment id, so TigerBeetle answers "exists" to a
     * retry instead of moving money twice. Business rejections (e.g. insufficient funds) are not retried and do not
     * count as circuit-breaker failures.
     */
    @Timeout(2000)
    @Retry(maxRetries = 3, delay = 50, jitter = 50, abortOn = LedgerClient.LedgerRejected.class)
    @CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5, delay = 5000, successThreshold = 3,
                    skipOn = LedgerClient.LedgerRejected.class)
    public String directTransfer(UUID paymentId, UUID debit, UUID credit, long amountMinor) {
        return ledger.direct(StepIds.of(paymentId, "direct"), debit, credit, amountMinor);
    }
}
