package dev.difme.exchange.rails.sim;

import dev.difme.exchange.rails.UpiReservePort;
import java.util.Map;
import java.util.Set;

/** In-memory UPI Reserve Pay: one block, many idempotent debits, never below zero, nothing after revoke. */
public final class SimUpiReserve implements UpiReservePort {
    private final Map<String, Block> blocks = Bounded.map();
    private final Set<String> debits = java.util.Collections.newSetFromMap(Bounded.map());

    @Override
    public synchronized Block reserve(String blockId, String vpa, long amountMinor) {
        if (amountMinor <= 0) throw new IllegalArgumentException("block must be positive");
        return blocks.computeIfAbsent(blockId, id -> new Block(id, vpa, amountMinor, 0, false));
    }

    @Override
    public synchronized boolean debit(String blockId, String debitId, long amountMinor) {
        Block b = blocks.get(blockId);
        if (b == null) return false;
        if (debits.contains(blockId + "|" + debitId)) return true;
        if (amountMinor <= 0 || amountMinor > b.availableMinor()) return false;
        debits.add(blockId + "|" + debitId);
        blocks.put(blockId, new Block(blockId, b.vpa(), b.blockedMinor(), b.debitedMinor() + amountMinor, b.revoked()));
        return true;
    }

    @Override
    public synchronized Block revoke(String blockId) {
        Block b = blocks.get(blockId);
        if (b == null) throw new IllegalArgumentException("no block " + blockId);
        Block n = new Block(blockId, b.vpa(), b.blockedMinor(), b.debitedMinor(), true);
        blocks.put(blockId, n);
        return n;
    }
}
