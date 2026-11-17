package dev.difme.core.common.projection;

/** Denormalised read model row (PDF §5.4 read projections). Mutable POJO for MyBatis mapping. */
public class PaymentView {
    public String paymentId;
    public String status;
    public long amountMinor;
    public String currency;
    public String channel;
    public String workflowId;
    public String debitAccountId;
    public String creditAccountId;
    public long version;
    public String updatedAt;

    public PaymentView() {}

    public PaymentView copy() {
        PaymentView v = new PaymentView();
        v.paymentId = paymentId; v.status = status; v.amountMinor = amountMinor; v.currency = currency;
        v.channel = channel; v.workflowId = workflowId; v.debitAccountId = debitAccountId;
        v.creditAccountId = creditAccountId; v.version = version; v.updatedAt = updatedAt;
        return v;
    }

    /** Stable fingerprint used by the replay engine to detect divergence. */
    public String fingerprint() {
        return String.join("|", paymentId, status, Long.toString(amountMinor), currency, String.valueOf(channel),
                String.valueOf(workflowId), Long.toString(version), String.valueOf(updatedAt));
    }
}
