package dev.difme.hub.flow;

import dev.difme.hub.cbs.Fineract;
import dev.difme.hub.psp.Hyperswitch;

/** Activities against the real systems. */
public final class StepsImpl implements Flows.Steps {
    private final Fineract cbs;
    private final Hyperswitch psp;

    public StepsImpl(Fineract cbs, Hyperswitch psp) {
        this.cbs = cbs;
        this.psp = psp;
    }

    @Override
    public long cbsTransfer(String key, String from, String to, long amountMinor, String note) {
        return cbs.transfer(key, from, to, amountMinor, note);
    }

    @Override
    public String pspRefund(String refundId, String paymentId, long amountMinor, String reason) {
        Hyperswitch.Outcome o = psp.refund(refundId, paymentId, amountMinor, reason);
        if ("failed".equals(o.status())) throw new Flows.PspRefused(o.errorCode() + " " + o.errorMessage());
        if (!o.succeeded()) throw new IllegalStateException("refund " + refundId + " still " + o.status());   // pending: retry
        return o.status();
    }
}
