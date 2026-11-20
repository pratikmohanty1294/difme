package dev.difme.exchange.rails;

/** Stablecoin settlement leg (v4.1 flow B): a transfer with a deterministic transaction reference per request id. */
public interface StablecoinPort {
    record Transfer(String requestId, String from, String to, long amountMinor, String asset) {}

    record Receipt(String requestId, String txRef, boolean confirmed) {}

    Receipt transfer(Transfer transfer);
}
