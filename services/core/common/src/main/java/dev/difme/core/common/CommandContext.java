package dev.difme.core.common;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything non-deterministic a command handler may need, supplied from outside (PDF §9 deterministic replay).
 * Live path: {@code now} = request time, {@code seed} = random. Replay path: {@code now} = event_timestamp header,
 * {@code seed} derived from the original event id — so replaying a 6-month-old event reproduces identical output.
 */
public record CommandContext(Instant now, long seed, String traceId) {
    public UUID newId(int ordinal) {
        return Uuid7.derived(now, seed * 31 + ordinal);
    }
}
