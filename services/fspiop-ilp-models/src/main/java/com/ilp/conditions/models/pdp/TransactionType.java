package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** TransactionType (FSPIOP 7.4.18). Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionType {
    private String scenario;
    private String subScenario;
    private String initiator;
    private String initiatorType;
    private Refund refundInfo;
    private String balanceOfPayments;

    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }
    public String getSubScenario() { return subScenario; }
    public void setSubScenario(String subScenario) { this.subScenario = subScenario; }
    public String getInitiator() { return initiator; }
    public void setInitiator(String initiator) { this.initiator = initiator; }
    public String getInitiatorType() { return initiatorType; }
    public void setInitiatorType(String initiatorType) { this.initiatorType = initiatorType; }
    public Refund getRefundInfo() { return refundInfo; }
    public void setRefundInfo(Refund refundInfo) { this.refundInfo = refundInfo; }
    public String getBalanceOfPayments() { return balanceOfPayments; }
    public void setBalanceOfPayments(String balanceOfPayments) { this.balanceOfPayments = balanceOfPayments; }

    @Override
    public boolean equals(Object x) {
        if (this == x) return true;
        if (!(x instanceof TransactionType o)) return false;
        return Objects.equals(scenario, o.scenario) && Objects.equals(subScenario, o.subScenario) && Objects.equals(initiator, o.initiator) && Objects.equals(initiatorType, o.initiatorType) && Objects.equals(refundInfo, o.refundInfo) && Objects.equals(balanceOfPayments, o.balanceOfPayments);
    }

    @Override
    public int hashCode() { return Objects.hash(scenario, subScenario, initiator, initiatorType, refundInfo, balanceOfPayments); }
}
