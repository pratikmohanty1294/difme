package dev.difme.core.common.saga;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Saga steps (PDF §7.2). Hosted by cmd-svc on {@link #TASK_QUEUE}. Every activity is idempotent: it is keyed by
 * payment id + step name, so Temporal retries never double-apply.
 */
@ActivityInterface
public interface PaymentActivities {
    String TASK_QUEUE = "payments-activities";

    @ActivityMethod String debitSource(TransferInput in);            // TigerBeetle pending transfer
    @ActivityMethod String reserveSettlement(TransferInput in);      // YugabyteDB serializable
    @ActivityMethod void notifyTargetBank(TransferInput in);         // gRPC + CloudEvents to the bank gateway
    @ActivityMethod String creditBeneficiary(TransferInput in);      // TigerBeetle post pending
    @ActivityMethod void releaseSettlement(TransferInput in);
    @ActivityMethod void markCompleted(TransferInput in, String ledgerTransferId);

    // Compensations — idempotent and safe to run more than once.
    @ActivityMethod void refundSource(TransferInput in);             // TigerBeetle void pending
    @ActivityMethod void cancelReservation(TransferInput in);
    @ActivityMethod void markCompensated(TransferInput in, String failedStep, String reason);
}
