package dev.difme.core.saga;

import static org.junit.jupiter.api.Assertions.*;

import dev.difme.core.common.saga.PaymentActivities;
import dev.difme.core.common.saga.PaymentWorkflow;
import dev.difme.core.common.saga.TransferInput;
import dev.difme.core.common.saga.TransferResult;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Saga behaviour in Temporal's in-memory test server (mirrors specs/tla/PaymentSaga.tla). */
class PaymentWorkflowTest {
    private TestWorkflowEnvironment env;
    private final List<String> calls = new ArrayList<>();
    private String failAt;

    private final TransferInput in = new TransferInput("0193f0a0-0000-7000-8000-000000000001", "k", "a", "b", 100, "INR", "BANKX", "t");

    class FakeSteps implements PaymentActivities {
        private void hit(String s) {
            calls.add(s);
            if (s.equals(failAt)) throw new IllegalStateException("boom at " + s);
        }
        public String debitSource(TransferInput i) { hit("debit"); return "d"; }
        public String reserveSettlement(TransferInput i) { hit("reserve"); return "r"; }
        public void notifyTargetBank(TransferInput i) { hit("notify"); }
        public String creditBeneficiary(TransferInput i) { hit("credit"); return "c"; }
        public void releaseSettlement(TransferInput i) { hit("release"); }
        public void markCompleted(TransferInput i, String t) { hit("completed"); }
        public void refundSource(TransferInput i) { hit("refund"); }
        public void cancelReservation(TransferInput i) { hit("cancel"); }
        public void markCompensated(TransferInput i, String f, String r) { hit("compensated:" + f); }
    }

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        env.newWorker(PaymentWorkflow.TASK_QUEUE).registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        Worker activities = env.newWorker(PaymentActivities.TASK_QUEUE);
        activities.registerActivitiesImplementations(new FakeSteps());
        env.start();
    }

    @AfterEach
    void tearDown() { env.close(); }

    private TransferResult run() {
        PaymentWorkflow wf = env.getWorkflowClient().newWorkflowStub(PaymentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(PaymentWorkflow.TASK_QUEUE).build());
        return wf.run(in);
    }

    @Test
    void happyPath() {
        TransferResult r = run();
        assertEquals("COMPLETED", r.status());
        assertEquals(List.of("debit", "reserve", "notify", "credit", "release", "completed"), calls);
    }

    @Test
    void notifyFailureCompensatesInReverseOrder() {
        failAt = "notify";
        TransferResult r = run();
        assertEquals("COMPENSATED", r.status());
        assertEquals("notify", r.failedStep());
        int notifyAttempts = (int) calls.stream().filter("notify"::equals).count();
        assertEquals(5, notifyAttempts, "retried with backoff before compensating");
        assertEquals(List.of("cancel", "refund", "compensated:notify"), calls.subList(calls.size() - 3, calls.size()));
        assertFalse(calls.contains("credit"));
    }
}
