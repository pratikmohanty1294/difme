package dev.difme.hub;

import static org.junit.jupiter.api.Assertions.*;

import dev.difme.hub.cbs.Fineract;
import dev.difme.hub.flow.FlowImpls;
import dev.difme.hub.flow.Flows;
import dev.difme.hub.flow.Flows.Result;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Saga behaviour of the hub's workflows in Temporal's in-memory server, with scripted steps. */
class FlowsTest {
    private TestWorkflowEnvironment env;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile String failKey;            // cbsTransfer key that fails
    private volatile RuntimeException failWith;
    private volatile boolean pspRefuses;
    private volatile int outagesLeft;            // transient failures before success

    class Steps implements Flows.Steps {
        public long cbsTransfer(String key, String from, String to, long amount, String note) {
            calls.add("cbs " + key + " " + from + ">" + to + " " + amount);
            if (outagesLeft > 0) { outagesLeft--; throw new IllegalStateException("core banking unreachable"); }
            if (key.equals(failKey)) throw failWith;
            return calls.size();
        }
        public String pspRefund(String refundId, String paymentId, long amount, String reason) {
            calls.add("psp refund " + refundId + " " + amount);
            if (pspRefuses) throw new Flows.PspRefused("13 Invalid amount");
            return "succeeded";
        }
    }

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker w = env.newWorker(Flows.TASK_QUEUE);
        w.registerWorkflowImplementationTypes(FlowImpls.CardSettlementImpl.class, FlowImpls.CardRefundImpl.class, FlowImpls.AccountTransferImpl.class);
        w.registerActivitiesImplementations(new Steps());
        env.start();
    }

    @AfterEach
    void tearDown() { env.close(); }

    private <W> W stub(Class<W> t) {
        return env.getWorkflowClient().newWorkflowStub(t, WorkflowOptions.newBuilder().setTaskQueue(Flows.TASK_QUEUE).build());
    }

    @Test
    void settlementCreditsTheMerchant() {
        Result r = stub(Flows.CardSettlement.class).settle(new Flows.Settlement("p1", "m1", 5000));
        assertEquals("SETTLED", r.status());
        assertEquals(List.of("cbs p1:settle card-scheme-settlement>merchant-m1 5000"), calls);
    }

    @Test
    void settlementRetriesAnOutageWithoutCompensating() {
        outagesLeft = 2;
        assertEquals("SETTLED", stub(Flows.CardSettlement.class).settle(new Flows.Settlement("p2", "m1", 5000)).status());
        assertEquals(3, calls.size());   // two failed tries, one success, same idempotency key each time
        assertTrue(calls.stream().allMatch(c -> c.startsWith("cbs p2:settle")));
    }

    @Test
    void settlementThatCannotBeBookedRefundsTheCard() {
        failKey = "p3:settle"; failWith = new Fineract.Rejected("account closed");
        Result r = stub(Flows.CardSettlement.class).settle(new Flows.Settlement("p3", "m1", 5000));
        assertEquals("REFUNDED", r.status());
        assertTrue(r.detail().contains("account closed"), r.detail());
        assertEquals("psp refund p3-unwind 5000", calls.get(1));
    }

    @Test
    void refundDebitsTheMerchantThenRefundsTheCard() {
        Result r = stub(Flows.CardRefund.class).refund(new Flows.Refund("r1", "p1", "m1", 2000, "returned"));
        assertEquals("REFUNDED", r.status());
        assertEquals(List.of("cbs r1:debit merchant-m1>card-scheme-settlement 2000", "psp refund r1 2000"), calls);
    }

    @Test
    void refundWithoutMerchantFundsTouchesNothingElse() {
        failKey = "r2:debit"; failWith = new Fineract.Insufficient("Insufficient account balance");
        Result r = stub(Flows.CardRefund.class).refund(new Flows.Refund("r2", "p1", "m1", 2000, "returned"));
        assertEquals("INSUFFICIENT_FUNDS", r.status());
        assertEquals(1, calls.size());    // not retried, no PSP call
    }

    @Test
    void refusedCardRefundCreditsTheMerchantBack() {
        pspRefuses = true;
        Result r = stub(Flows.CardRefund.class).refund(new Flows.Refund("r3", "p1", "m1", 2000, "returned"));
        assertEquals("FAILED", r.status());
        assertEquals(List.of("cbs r3:debit merchant-m1>card-scheme-settlement 2000", "psp refund r3 2000",
                "cbs r3:undo card-scheme-settlement>merchant-m1 2000"), calls);
    }

    @Test
    void transferReportsInsufficientFundsWithoutRetrying() {
        failKey = "t1"; failWith = new Fineract.Insufficient("Insufficient account balance");
        Result r = stub(Flows.AccountTransfer.class).transfer(new Flows.Transfer("t1", "buyer-1", "escrow", 100, "hold"));
        assertEquals("INSUFFICIENT_FUNDS", r.status());
        assertEquals(1, calls.size());
    }

    @Test
    void repeatedHubCallReturnsTheFirstResultWithoutMovingMoneyAgain() {
        Hub hub = new Hub(env.getWorkflowClient(), null, null);
        Result a = hub.transfer("t2", "buyer-1", "escrow", 700, "hold");
        Result b = hub.transfer("t2", "buyer-1", "escrow", 700, "hold");
        assertEquals("DONE", a.status());
        assertEquals(a, b);
        assertEquals(1, calls.size());
    }

    @Test
    void hubRejectsMalformedRequests() {
        Hub hub = new Hub(env.getWorkflowClient(), null, null);
        assertThrows(IllegalArgumentException.class, () -> hub.transfer("t 3", "buyer-1", "escrow", 1, null));
        assertThrows(IllegalArgumentException.class, () -> hub.transfer("t3", "Buyer!", "escrow", 1, null));
        assertThrows(IllegalArgumentException.class, () -> hub.transfer("t3", "buyer-1", "buyer-1", 1, null));
        assertThrows(IllegalArgumentException.class, () -> hub.transfer("t3", "buyer-1", "escrow", 0, null));
        assertTrue(calls.isEmpty());
    }
}
