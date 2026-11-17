package dev.difme.core.common.resilience;

import java.util.function.LongSupplier;

/**
 * Local token bucket (ADR-017): the degraded-mode rate limit used when the shared rate limiter is unreachable or its
 * circuit is open. Set conservatively (per pod), so an outage of the shared limiter fails safe: payments keep flowing
 * but at a capped rate, rather than failing closed (outage) or open (no limit).
 */
public final class TokenBucket {
    private final double ratePerNano;
    private final double burst;
    private final LongSupplier clock;
    private double tokens;
    private long last;

    public TokenBucket(double perSecond, int burst) {
        this(perSecond, burst, System::nanoTime);
    }

    TokenBucket(double perSecond, int burst, LongSupplier nanoClock) {
        if (perSecond <= 0 || burst < 1) throw new IllegalArgumentException("rate > 0 and burst >= 1");
        this.ratePerNano = perSecond / 1_000_000_000d;
        this.burst = burst;
        this.clock = nanoClock;
        this.tokens = burst;
        this.last = nanoClock.getAsLong();
    }

    public synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        tokens = Math.min(burst, tokens + (now - last) * ratePerNano);
        last = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
