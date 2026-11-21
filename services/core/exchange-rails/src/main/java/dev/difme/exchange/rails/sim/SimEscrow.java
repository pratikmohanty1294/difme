package dev.difme.exchange.rails.sim;

import dev.difme.exchange.rails.EscrowPort;
import java.util.Map;
import java.util.Set;

/** In-memory escrow enforcing conservation (released + refunded ≤ held) with idempotent hold/release/refund. */
public final class SimEscrow implements EscrowPort {
    private final Map<String, Hold> holds = Bounded.map();
    private final Set<String> releases = java.util.Collections.newSetFromMap(Bounded.map());

    @Override
    public synchronized Hold hold(String holdId, String payer, long amountMinor) {
        if (amountMinor <= 0) throw new IllegalArgumentException("hold must be positive");
        Hold existing = holds.get(holdId);
        if (existing != null) {
            if (existing.heldMinor() != amountMinor || !existing.payer().equals(payer)) {
                throw new IllegalStateException("hold " + holdId + " already exists with different terms");
            }
            return existing;
        }
        Hold h = new Hold(holdId, payer, amountMinor, 0, 0, State.HELD);
        holds.put(holdId, h);
        return h;
    }

    @Override
    public synchronized Hold release(String holdId, String releaseId, String payee, long amountMinor) {
        Hold h = require(holdId);
        if (!releases.add(holdId + "|" + releaseId)) return h;   // retried release: already applied
        if (h.state() == State.REFUNDED || amountMinor <= 0 || amountMinor > h.remainingMinor()) {
            releases.remove(holdId + "|" + releaseId);
            throw new IllegalStateException("release " + amountMinor + " exceeds remaining " + h.remainingMinor() + " on " + holdId);
        }
        long released = h.releasedMinor() + amountMinor;
        Hold n = new Hold(holdId, h.payer(), h.heldMinor(), released, h.refundedMinor(),
                released + h.refundedMinor() == h.heldMinor() ? State.SETTLED : State.PARTLY_RELEASED);   // a partial refund counts
        holds.put(holdId, n);
        return n;
    }

    @Override
    public synchronized Hold refund(String holdId) {
        Hold h = require(holdId);
        if (h.remainingMinor() == 0) return h;
        Hold n = new Hold(holdId, h.payer(), h.heldMinor(), h.releasedMinor(), h.refundedMinor() + h.remainingMinor(), State.REFUNDED);
        holds.put(holdId, n);
        return n;
    }

    @Override
    public synchronized Hold refund(String holdId, String refundId, long amountMinor) {
        Hold h = require(holdId);
        if (!releases.add(holdId + "|refund|" + refundId)) return h;   // retried refund: already applied
        if (h.state() == State.REFUNDED || amountMinor <= 0 || amountMinor > h.remainingMinor()) {
            releases.remove(holdId + "|refund|" + refundId);
            throw new IllegalStateException("refund " + amountMinor + " exceeds remaining " + h.remainingMinor() + " on " + holdId);
        }
        long refunded = h.refundedMinor() + amountMinor;
        boolean done = h.releasedMinor() + refunded == h.heldMinor();
        Hold n = new Hold(holdId, h.payer(), h.heldMinor(), h.releasedMinor(), refunded,
                done ? (h.releasedMinor() == 0 ? State.REFUNDED : State.SETTLED) : h.state());
        holds.put(holdId, n);
        return n;
    }

    @Override
    public synchronized Hold get(String holdId) { return require(holdId); }

    /** Every hold, for the exchange's durable state (the simulator has no ledger of its own). */
    public synchronized java.util.List<Hold> all() { return java.util.List.copyOf(holds.values()); }

    /**
     * Puts back a stored hold after a restart. Releases and refunds are not replayed, so their retry ids are not known
     * any more: a retried release after a restart is rejected by the balance check instead of being a no-op.
     */
    public synchronized void restore(Hold h) { holds.put(h.holdId(), h); }

    private Hold require(String holdId) {
        Hold h = holds.get(holdId);
        if (h == null) throw new IllegalArgumentException("no hold " + holdId);
        return h;
    }
}
