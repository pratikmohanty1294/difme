package dev.difme.core.common.process;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Started by process-gateway for BPMN topic {@code temporal:AccountProvisioningWorkflow}. */
@WorkflowInterface
public interface AccountProvisioningWorkflow {
    String TASK_QUEUE = "accounts-saga";

    @WorkflowMethod
    String provision(ProvisioningInput input);
}
