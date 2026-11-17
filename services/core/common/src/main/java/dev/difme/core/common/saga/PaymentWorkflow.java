package dev.difme.core.common.saga;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** PDF §6.1 / §7.2 — durable cross-bank transfer saga. Workflow id = payment UUIDv7. */
@WorkflowInterface
public interface PaymentWorkflow {
    String TASK_QUEUE = "payments-saga";

    @WorkflowMethod
    TransferResult run(TransferInput input);

    @QueryMethod
    String currentStep();
}
