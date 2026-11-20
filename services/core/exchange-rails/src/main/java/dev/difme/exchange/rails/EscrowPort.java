package dev.difme.exchange.rails;

/**
 * Payment-aggregator escrow (RBI-licensed PA in production): buyer funds are held, then released to payees or
 * refunded. Conservation: released + refunded never exceeds held, and every operation is idempotent by its id.
 */
public interface EscrowPort {
    enum State { HELD, PARTLY_RELEASED, SETTLED, REFUNDED }

    record Hold(String holdId, String payer, long heldMinor, long releasedMinor, long refundedMinor, State state) {
        public long remainingMinor() { return heldMinor - releasedMinor - refundedMinor; }
    }

    Hold hold(String holdId, String payer, long amountMinor);

    /** Releases part of the hold to a payee; releaseId makes retries safe. */
    Hold release(String holdId, String releaseId, String payee, long amountMinor);

    /** Refunds whatever remains to the payer. */
    Hold refund(String holdId);

    Hold get(String holdId);
}
