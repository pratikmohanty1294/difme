package dev.difme.core.cmd.idempotency;

import java.util.Optional;
import java.util.UUID;

/** PDF §9 command-layer dedup: linearizable, UUIDv7 key, 24h TTL (TLA+ Idempotency.tla). */
public interface IdempotencyStore {
    /** Atomically claims the key. Returns the previously stored payment id if the key is a live duplicate. */
    Optional<UUID> claim(UUID idempotencyKey, UUID paymentId, long nowEpochMs);
}
