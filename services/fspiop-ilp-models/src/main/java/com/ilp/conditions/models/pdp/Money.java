package com.ilp.conditions.models.pdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Money (FSPIOP 7.4.12): currency (ISO 4217) and amount as a decimal string. Written from the specification by DIFME. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Money {
    private String currency;
    private String amount;

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    @Override
    public boolean equals(Object x) {
        if (this == x) return true;
        if (!(x instanceof Money o)) return false;
        return Objects.equals(currency, o.currency) && Objects.equals(amount, o.amount);
    }

    @Override
    public int hashCode() { return Objects.hash(currency, amount); }
}
