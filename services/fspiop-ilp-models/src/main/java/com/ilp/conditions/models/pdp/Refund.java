package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Refund (FSPIOP 7.4.16). Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Refund {
    private String originalTransactionId;
    private String refundReason;

    public String getOriginalTransactionId() { return originalTransactionId; }
    public void setOriginalTransactionId(String originalTransactionId) { this.originalTransactionId = originalTransactionId; }
    public String getRefundReason() { return refundReason; }
    public void setRefundReason(String refundReason) { this.refundReason = refundReason; }

    @Override
    public boolean equals(Object x) {
        if (this == x) return true;
        if (!(x instanceof Refund o)) return false;
        return Objects.equals(originalTransactionId, o.originalTransactionId) && Objects.equals(refundReason, o.refundReason);
    }

    @Override
    public int hashCode() { return Objects.hash(originalTransactionId, refundReason); }
}
