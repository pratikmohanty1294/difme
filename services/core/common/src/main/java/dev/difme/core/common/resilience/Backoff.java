package dev.difme.core.common.resilience;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter (ADR-017): sleep = random(0, min(cap, base * 2^attempt)).
 * Full jitter spreads retries of many clients that failed together, so they don't retry in lock-step.
 */
public record Backoff(Duration base, Duration cap) {
    public static final Backoff DEFAULT = new Backoff(Duration.ofMillis(10), Duration.ofMillis(500));

    /** Upper bound of the sleep before retry {@code attempt} (0-based), in nanoseconds. */
    public long ceilingNanos(int attempt) {
        long ceiling = base.toNanos() << Math.min(attempt, 30);
        return ceiling <= 0 ? cap.toNanos() : Math.min(cap.toNanos(), ceiling);
    }

    public long delayNanos(int attempt) {
        return ThreadLocalRandom.current().nextLong(ceilingNanos(attempt) + 1);
    }

    public void sleep(int attempt) {
        try {
            Thread.sleep(Duration.ofNanos(delayNanos(attempt)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during backoff", e);
        }
    }
}
