package dev.difme.core.saga;

import dev.difme.core.common.process.AccountActivities;
import dev.difme.core.common.process.AccountProvisioningWorkflow;
import dev.difme.core.common.process.ProcessCallbacks;
import dev.difme.core.common.process.ProvisioningInput;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;

/**
 * Inter-process orchestration owned by Temporal (ADR-008): reads the limit from the DMN-backed config, creates the
 * ledger account, then wakes the waiting BPMN process via its message event.
 */
public class AccountProvisioningWorkflowImpl implements AccountProvisioningWorkflow {
    private final RetryOptions retry = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(500)).setBackoffCoefficient(2.0).setMaximumAttempts(10).build();

    private final AccountActivities accounts = Workflow.newActivityStub(AccountActivities.class,
            ActivityOptions.newBuilder().setTaskQueue(AccountActivities.TASK_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(10)).setRetryOptions(retry).build());

    private final ProcessCallbacks process = Workflow.newActivityStub(ProcessCallbacks.class,
            ActivityOptions.newBuilder().setTaskQueue(ProcessCallbacks.TASK_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(10)).setRetryOptions(retry).build());

    @Override
    public String provision(ProvisioningInput in) {
        long limit = accounts.dailyLimit("MOBILE", in.riskLevel());
        String accountId = accounts.createLedgerAccount(in, limit);
        process.correlate("account-provisioned", in.processInstanceId(),
                Map.of("accountId", accountId, "dailyLimitMinor", limit));
        return accountId;
    }
}
