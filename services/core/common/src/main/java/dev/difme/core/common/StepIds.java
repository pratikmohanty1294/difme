package dev.difme.core.common;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic ids for the side effects of one payment step (ledger transfers, events). A retry, a recovery sweep
 * or a Temporal re-run produces the same id, so TigerBeetle and the outbox see a duplicate instead of a second effect.
 */
public final class StepIds {
    private StepIds() {}

    public static UUID of(String paymentId, String step) {
        return UUID.nameUUIDFromBytes((paymentId + "/" + step).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID of(UUID paymentId, String step) {
        return of(paymentId.toString(), step);
    }
}
