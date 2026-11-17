package dev.difme.core.common.projection;

import dev.difme.core.common.events.PaymentEvent;
import dev.difme.core.common.events.PaymentEvent.*;

/**
 * Pure projection function: (state, event) → new state. Used by projection-svc (live) and replay-engine
 * (audit replay). No clock, no randomness, no I/O — enforced by policies/semgrep/purity.yaml.
 */
public final class PaymentProjector {
    private PaymentProjector() {}

    public static PaymentView apply(PaymentView current, PaymentEvent event) {
        PaymentView v = current == null ? new PaymentView() : current.copy();
        switch (event) {
            case PaymentInitiated e -> {
                v.paymentId = e.paymentId().toString();
                v.status = "ACCEPTED";
                v.amountMinor = e.amount().amountMinor();
                v.currency = e.amount().currency();
                v.channel = e.channel();
                v.debitAccountId = e.debitAccountId().toString();
                v.creditAccountId = e.creditAccountId().toString();
            }
            case PaymentCompleted e -> { v.status = "COMPLETED"; v.workflowId = e.workflowId(); }
            case PaymentCompensated e -> { v.status = "COMPENSATED"; v.workflowId = e.workflowId(); }
            case PaymentRejected e -> { v.paymentId = e.paymentId().toString(); v.status = "REJECTED"; }
        }
        v.version = v.version + 1;
        v.updatedAt = event.occurredAt().toString();   // event time, never wall-clock time
        return v;
    }
}
