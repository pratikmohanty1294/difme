package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Transaction (FSPIOP 7.4.17): what an ILP packet carries for a quote/transfer. Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Transaction {
    private String transactionId;
    private String quoteId;
    private Party payee;
    private Party payer;
    private Money amount;
    private TransactionType transactionType;
    private String note;
    private ExtensionList extensionList;

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getQuoteId() { return quoteId; }
    public void setQuoteId(String quoteId) { this.quoteId = quoteId; }
    public Party getPayee() { return payee; }
    public void setPayee(Party payee) { this.payee = payee; }
    public Party getPayer() { return payer; }
    public void setPayer(Party payer) { this.payer = payer; }
    public Money getAmount() { return amount; }
    public void setAmount(Money amount) { this.amount = amount; }
    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public ExtensionList getExtensionList() { return extensionList; }
    public void setExtensionList(ExtensionList extensionList) { this.extensionList = extensionList; }

    @Override
    public boolean equals(Object x) {
        if (this == x) return true;
        if (!(x instanceof Transaction o)) return false;
        return Objects.equals(transactionId, o.transactionId) && Objects.equals(quoteId, o.quoteId) && Objects.equals(payee, o.payee) && Objects.equals(payer, o.payer) && Objects.equals(amount, o.amount) && Objects.equals(transactionType, o.transactionType) && Objects.equals(note, o.note) && Objects.equals(extensionList, o.extensionList);
    }

    @Override
    public int hashCode() { return Objects.hash(transactionId, quoteId, payee, payer, amount, transactionType, note, extensionList); }
}
