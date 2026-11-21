package dev.difme.hub.flow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** The payment hub's Temporal contracts. Card numbers never appear here: only ids, account names and amounts. */
public final class Flows {
    private Flows() {}

    public static final String TASK_QUEUE = "payment-hub";
    /** The card scheme's settlement account at the CBS: card money owed to merchants comes from here. */
    public static final String CARD_SETTLEMENT = "card-scheme-settlement";

    /** Outcome of a hub operation. status: SETTLED, REFUNDED, DONE, DECLINED, INSUFFICIENT_FUNDS, REJECTED, FAILED. */
    public record Result(String id, String status, String detail) {}

    public record Settlement(String paymentId, String merchant, long amountMinor) {}

    public record Refund(String refundId, String paymentId, String merchant, long amountMinor, String reason) {}

    public record Transfer(String transferId, String from, String to, long amountMinor, String note) {}

    @ActivityInterface
    public interface Steps {
        /** CBS account transfer, idempotent by key. Fails without retry on Fineract$Insufficient / Fineract$Rejected. */
        @ActivityMethod long cbsTransfer(String key, String from, String to, long amountMinor, String note);

        /** PSP refund, idempotent by refundId. Fails without retry (PspRefused) when the network refuses it. */
        @ActivityMethod String pspRefund(String refundId, String paymentId, long amountMinor, String reason);
    }

    /** After the card network approved a charge: credit the merchant at the CBS; if that can't be done, refund the card. */
    @WorkflowInterface
    public interface CardSettlement {
        @WorkflowMethod Result settle(Settlement s);
    }

    /** Debit the merchant at the CBS, then refund the card at the PSP; if the PSP refuses, credit the merchant back. */
    @WorkflowInterface
    public interface CardRefund {
        @WorkflowMethod Result refund(Refund r);
    }

    /** One CBS transfer between named accounts (escrow hold, release, refund), durable and idempotent. */
    @WorkflowInterface
    public interface AccountTransfer {
        @WorkflowMethod Result transfer(Transfer t);
    }

    /** The PSP refused an operation for good (e.g. the network declined a refund). */
    public static final class PspRefused extends RuntimeException {
        public PspRefused(String m) { super(m); }
    }
}
