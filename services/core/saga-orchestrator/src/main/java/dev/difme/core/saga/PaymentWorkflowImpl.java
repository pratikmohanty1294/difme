package dev.difme.core.saga;

import dev.difme.core.common.saga.PaymentActivities;
import dev.difme.core.common.saga.PaymentWorkflow;
import dev.difme.core.common.saga.TransferInput;
import dev.difme.core.common.saga.TransferResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * PDF §7.2 cross-bank transfer, verified by specs/tla/PaymentSaga.tla:
 * debit → reserve → notify → credit → release; any failure before credit compensates (refund + cancel reservation).
 * Versioning (PDF §6.1): in-flight workflows keep the logic they started with; Workflow.getVersion gates changes.
 */
public class PaymentWorkflowImpl implements PaymentWorkflow {
    private final PaymentActivities steps = Workflow.newActivityStub(PaymentActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(PaymentActivities.TASK_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(200))
                            .setBackoffCoefficient(2.0)          // exponential backoff (§7.2 notify step)
                            .setMaximumAttempts(5)
                            .setDoNotRetry("dev.difme.core.cmd.ledger.LedgerClient$LedgerRejected")
                            .build())
                    .build());

    private String step = "init";

    @Override
    public TransferResult run(TransferInput in) {
        Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
        try {
            step = "debit";
            steps.debitSource(in);
            saga.addCompensation(steps::refundSource, in);

            int v = Workflow.getVersion("settlement-reserve", Workflow.DEFAULT_VERSION, 1);
            if (v >= 1) {
                step = "reserve";
                steps.reserveSettlement(in);
                saga.addCompensation(steps::cancelReservation, in);
            }

            step = "notify";
            steps.notifyTargetBank(in);

            step = "credit";
            String transferId = steps.creditBeneficiary(in);     // point of no return: no compensation after credit

            if (v >= 1) {
                step = "release";
                steps.releaseSettlement(in);
            }
            step = "completed";
            steps.markCompleted(in, transferId);
            return new TransferResult(in.paymentId(), "COMPLETED", transferId, null);
        } catch (ActivityFailure e) {
            String failed = step;
            step = "compensating";
            saga.compensate();
            steps.markCompensated(in, failed, String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
            step = "compensated";
            return new TransferResult(in.paymentId(), "COMPENSATED", null, failed);
        }
    }

    @Override
    public String currentStep() {
        return step;
    }
}
