package dev.difme.core.common.saga;

/** Serializable saga input (Temporal payload). Plain types only. */
public record TransferInput(String paymentId, String idempotencyKey, String debitAccountId, String creditAccountId,
                            long amountMinor, String currency, String targetBank, String traceId) {}
