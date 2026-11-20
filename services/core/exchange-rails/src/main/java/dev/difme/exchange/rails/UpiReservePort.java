package dev.difme.exchange.rails;

/** UPI Reserve Pay (single block, multiple debits): a mandate-backed block on the payer's account, debited in parts. */
public interface UpiReservePort {
    record Block(String blockId, String vpa, long blockedMinor, long debitedMinor, boolean revoked) {
        public long availableMinor() { return revoked ? 0 : blockedMinor - debitedMinor; }
    }

    Block reserve(String blockId, String vpa, long amountMinor);

    /** Debits from the block; false when the block can't cover it (no partial debit). Idempotent by debitId. */
    boolean debit(String blockId, String debitId, long amountMinor);

    Block revoke(String blockId);
}
