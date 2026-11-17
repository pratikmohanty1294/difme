package dev.difme.core.saga;

import static org.junit.jupiter.api.Assertions.*;

import dev.difme.core.common.process.AccountActivities;
import dev.difme.core.common.process.AccountProvisioningWorkflow;
import dev.difme.core.common.process.ProcessCallbacks;
import dev.difme.core.common.process.ProvisioningInput;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountProvisioningWorkflowTest {
    @Test
    void provisionsThenCorrelatesBpmnMessage() {
        List<String> log = new ArrayList<>();
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        env.newWorker(AccountProvisioningWorkflow.TASK_QUEUE).registerWorkflowImplementationTypes(AccountProvisioningWorkflowImpl.class);
        env.newWorker(AccountActivities.TASK_QUEUE).registerActivitiesImplementations(new AccountActivities() {
            public String createLedgerAccount(ProvisioningInput i, long limit) { log.add("account:" + limit); return "acc-1"; }
            public long dailyLimit(String channel, String risk) { log.add("limit:" + risk); return 5_000_000L; }
        });
        env.newWorker(ProcessCallbacks.TASK_QUEUE).registerActivitiesImplementations((ProcessCallbacks)
                (msg, pid, vars) -> log.add("correlate:" + msg + ":" + pid + ":" + vars.get("accountId")));
        env.start();
        AccountProvisioningWorkflow wf = env.getWorkflowClient().newWorkflowStub(AccountProvisioningWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(AccountProvisioningWorkflow.TASK_QUEUE).build());
        assertEquals("acc-1", wf.provision(new ProvisioningInput("pi-9", "bk-9", "INDIVIDUAL", "INR", "MEDIUM")));
        assertEquals(List.of("limit:MEDIUM", "account:5000000", "correlate:account-provisioned:pi-9:acc-1"), log);
        env.close();
    }
}
