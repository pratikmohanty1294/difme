package dev.difme.exchange.rails.sim;

import dev.difme.exchange.rails.EscrowPort;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory escrow enforcing conservation (released + refunded ≤ held) with idempotent hold/release/refund. */
public final class SimEscrow implements EscrowPort {
    private final Map<String, Hold> holds = new HashMap<>();
    private final Set<String> releases = new HashSet<>();

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
                released == h.heldMinor() ? State.SETTLED : State.PARTLY_RELEASED);
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
    public synchronized Hold get(String holdId) { return require(holdId); }

    private Hold require(String holdId) {
        Hold h = holds.get(holdId);
        if (h == null) throw new IllegalArgumentException("no hold " + holdId);
        return h;
    }
}
