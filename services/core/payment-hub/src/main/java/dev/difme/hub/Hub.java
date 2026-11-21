package dev.difme.hub;

import dev.difme.hub.cbs.Fineract;
import dev.difme.hub.flow.FlowImpls;
import dev.difme.hub.flow.Flows;
import dev.difme.hub.flow.Flows.Result;
import dev.difme.hub.flow.StepsImpl;
import dev.difme.hub.psp.Hyperswitch;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.regex.Pattern;

/**
 * DIFME's payment hub: channels call it; it drives the PSP (Hyperswitch, whose card processor is DIFME's acquirer on
 * the card network) and the CBS (Fineract). Money movements run as Temporal workflows keyed by the caller's id, so a
 * repeated call returns the first call's result instead of moving money twice.
 */
public final class Hub {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,48}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private final WorkflowClient client;
    private final Fineract cbs;
    private final Hyperswitch psp;

    public Hub(WorkflowClient client, Fineract cbs, Hyperswitch psp) {
        this.client = client;
        this.cbs = cbs;
        this.psp = psp;
    }

    /** Registers the hub's workflows and activities on a worker of the given factory (not started). */
    public static void register(WorkerFactory factory, Fineract cbs, Hyperswitch psp) {
        Worker w = factory.newWorker(Flows.TASK_QUEUE);
        w.registerWorkflowImplementationTypes(FlowImpls.CardSettlementImpl.class, FlowImpls.CardRefundImpl.class,
                FlowImpls.AccountTransferImpl.class);
        w.registerActivitiesImplementations(new StepsImpl(cbs, psp));
    }

    /**
     * Card payment to a merchant: the PSP charges the card (the network approves or declines it, synchronously), then a
     * workflow credits the merchant at the CBS, refunding the card if that can't be done. The card goes to the PSP only.
     */
    public Result pay(String paymentId, String merchant, long amountMinor, String currency, Hyperswitch.Card card) {
        check(paymentId, merchant, amountMinor);
        Hyperswitch.Outcome o = psp.charge(paymentId, amountMinor, currency, card, "DIFME order " + paymentId);
        if (!o.succeeded()) {
            return new Result(paymentId, "DECLINED", (o.errorCode() == null ? o.status() : o.errorCode()) + ": " + o.errorMessage());
        }
        return run("card-settle-" + paymentId, "CardSettlement", new Flows.Settlement(paymentId, merchant, o.amountMinor()));
    }

    public Result refund(String refundId, String paymentId, String merchant, long amountMinor, String reason) {
        check(refundId, merchant, amountMinor);
        if (!ID.matcher(paymentId).matches()) throw new IllegalArgumentException("paymentId: 1-48 letters, digits, _ or -");
        return run("card-refund-" + refundId, "CardRefund",
                new Flows.Refund(refundId, paymentId, merchant, amountMinor, reason == null ? "requested" : reason));
    }

    /** CBS transfer between named accounts (escrow hold: buyer → escrow; release: escrow → payee; refund: escrow → buyer). */
    public Result transfer(String transferId, String from, String to, long amountMinor, String note) {
        check(transferId, from, amountMinor);
        if (!NAME.matcher(to).matches() || from.equals(to)) throw new IllegalArgumentException("to: a different account name");
        return run("transfer-" + transferId, "AccountTransfer", new Flows.Transfer(transferId, from, to, amountMinor, note == null ? transferId : note));
    }

    /** Money arriving from outside (a bank transfer in), idempotent by depositId. */
    public Result deposit(String depositId, String account, long amountMinor) {
        check(depositId, account, amountMinor);
        try {
            return new Result(depositId, "DONE", "cbs transaction " + cbs.deposit("deposit-" + depositId, account, amountMinor, "deposit " + depositId));
        } catch (Fineract.Rejected e) {
            return new Result(depositId, "REJECTED", e.getMessage());
        }
    }

    public long balanceMinor(String account) {
        if (!NAME.matcher(account).matches()) throw new IllegalArgumentException("account: lower-case letters, digits and -");
        return cbs.balanceMinor(account);
    }

    private static void check(String id, String account, long amountMinor) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("id: 1-48 letters, digits, _ or -");
        if (account == null || !NAME.matcher(account).matches()) throw new IllegalArgumentException("account: lower-case letters, digits and -");
        if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    }

    /** Starts the workflow under its business id, or joins the one already started with that id. */
    private Result run(String workflowId, String workflowType, Object input) {
        WorkflowStub wf = client.newUntypedWorkflowStub(workflowType,
                WorkflowOptions.newBuilder().setTaskQueue(Flows.TASK_QUEUE).setWorkflowId(workflowId)
                        // Temporal's default lets a finished id start again, which would move the money twice.
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build());
        try {
            wf.start(input);
        } catch (WorkflowExecutionAlreadyStarted e) {
            // same id again: answer with the first run's result
        }
        return client.newUntypedWorkflowStub(workflowId).getResult(Result.class);
    }
}
