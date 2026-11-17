package dev.difme.core.common;

/** Integer minor units only — money is never a floating-point value. */
public record Money(long amountMinor, String currency) {
    public Money {
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be positive");
        if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("ISO-4217 currency required");
    }
}
