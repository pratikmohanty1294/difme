package dev.difme.core.cmd.idempotency;

import java.util.Optional;
import java.util.UUID;

/** PDF §9 command-layer dedup: linearizable, UUIDv7 key, 24h TTL (TLA+ Idempotency.tla). */
public interface IdempotencyStore {
    /**
     * Claims the key inside the caller's transaction (ADR-017: the claim commits or rolls back together with the
     * payment and its outbox event). Returns the previously stored payment id if the key is a live duplicate.
     */
    Optional<UUID> claim(UUID idempotencyKey, UUID paymentId, long nowEpochMs);

    /** Read-only lookup of a live key, used before rate limiting so client retries cost nothing. */
    Optional<UUID> find(UUID idempotencyKey, long nowEpochMs);
}
