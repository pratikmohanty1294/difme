package dev.difme.hub.flow;

import dev.difme.hub.flow.Flows.Result;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** Workflow implementations. Business failures are not retried; outages are, with backoff. */
public final class FlowImpls {
    private FlowImpls() {}

    static final String[] FINAL = {"dev.difme.hub.cbs.Fineract$Insufficient", "dev.difme.hub.cbs.Fineract$Rejected",
            "dev.difme.hub.flow.Flows$PspRefused", "java.lang.IllegalArgumentException"};

    /** Forward steps: up to 8 tries over roughly two minutes, then the workflow compensates. */
    static Flows.Steps steps() {
        return Workflow.newActivityStub(Flows.Steps.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(40))
                .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofMillis(500)).setBackoffCoefficient(2)
                        .setMaximumInterval(Duration.ofSeconds(30)).setMaximumAttempts(8).setDoNotRetry(FINAL).build())
                .build());
    }

    /** Compensations must finish (money is out of place until they do): unlimited retries, capped backoff. */
    static Flows.Steps compensations() {
        return Workflow.newActivityStub(Flows.Steps.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(40))
                .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(1)).setBackoffCoefficient(2)
                        .setMaximumInterval(Duration.ofMinutes(2)).setMaximumAttempts(0).setDoNotRetry(FINAL).build())
                .build());
    }

    /** The failure type and message behind an activity failure. */
    static String[] cause(ActivityFailure f) {
        if (f.getCause() instanceof ApplicationFailure a) return new String[] {a.getType(), a.getOriginalMessage()};
        return new String[] {"", String.valueOf(f.getCause() == null ? f.getMessage() : f.getCause().getMessage())};
    }

    public static final class CardSettlementImpl implements Flows.CardSettlement {
        @Override
        public Result settle(Flows.Settlement s) {
            try {
                steps().cbsTransfer(s.paymentId() + ":settle", Flows.CARD_SETTLEMENT, "merchant-" + s.merchant(), s.amountMinor(),
                        "card payment " + s.paymentId());
                return new Result(s.paymentId(), "SETTLED", "merchant-" + s.merchant() + " credited");
            } catch (ActivityFailure f) {
                // The merchant can't be credited: give the cardholder their money back rather than leave it in limbo.
                String why = cause(f)[1];
                try {
                    compensations().pspRefund(s.paymentId() + "-unwind", s.paymentId(), s.amountMinor(), "settlement failed");
                    return new Result(s.paymentId(), "REFUNDED", "merchant credit failed (" + why + "); card refunded");
                } catch (ActivityFailure g) {
                    return new Result(s.paymentId(), "FAILED", "merchant credit failed (" + why + ") and the refund was refused ("
                            + cause(g)[1] + "): needs an operator");
                }
            }
        }
    }

    public static final class CardRefundImpl implements Flows.CardRefund {
        @Override
        public Result refund(Flows.Refund r) {
            String merchant = "merchant-" + r.merchant();
            try {
                steps().cbsTransfer(r.refundId() + ":debit", merchant, Flows.CARD_SETTLEMENT, r.amountMinor(), "refund " + r.refundId());
            } catch (ActivityFailure f) {
                String[] c = cause(f);
                return new Result(r.refundId(), c[0].endsWith("Insufficient") ? "INSUFFICIENT_FUNDS" : "REJECTED", c[1]);
            }
            try {
                String status = steps().pspRefund(r.refundId(), r.paymentId(), r.amountMinor(), r.reason());
                return new Result(r.refundId(), "REFUNDED", "card refund " + status);
            } catch (ActivityFailure f) {
                compensations().cbsTransfer(r.refundId() + ":undo", Flows.CARD_SETTLEMENT, merchant, r.amountMinor(),
                        "refund " + r.refundId() + " undone");
                return new Result(r.refundId(), "FAILED", "card refund refused (" + cause(f)[1] + "); merchant debit undone");
            }
        }
    }

    public static final class AccountTransferImpl implements Flows.AccountTransfer {
        @Override
        public Result transfer(Flows.Transfer t) {
            try {
                long id = steps().cbsTransfer(t.transferId(), t.from(), t.to(), t.amountMinor(), t.note());
                return new Result(t.transferId(), "DONE", "cbs transfer " + id);
            } catch (ActivityFailure f) {
                String[] c = cause(f);
                return new Result(t.transferId(), c[0].endsWith("Insufficient") ? "INSUFFICIENT_FUNDS" : "REJECTED", c[1]);
            }
        }
    }
}
