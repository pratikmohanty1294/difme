package dev.difme.exchange.rails.sim;

import dev.difme.exchange.rails.StablecoinPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Simulated stablecoin leg: confirmed at once, transaction reference derived from the request id (idempotent). */
public final class SimStablecoin implements StablecoinPort {
    private final Map<String, Receipt> receipts = new ConcurrentHashMap<>();

    @Override
    public Receipt transfer(Transfer t) {
        if (t.amountMinor() <= 0) throw new IllegalArgumentException("transfer must be positive");
        return receipts.computeIfAbsent(t.requestId(),
                id -> new Receipt(id, "0x" + SimAgentCredential.sha256(id + t.from() + t.to() + t.amountMinor() + t.asset()), true));
    }
}
