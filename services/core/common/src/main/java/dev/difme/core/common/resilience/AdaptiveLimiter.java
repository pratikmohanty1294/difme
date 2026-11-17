package dev.difme.core.common.resilience;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Adaptive concurrency limit for load shedding (ADR-017), AIMD on latency: while requests finish within the target
 * latency the limit grows by ~1 per window (additive increase); a slow or failed request cuts it by 10%
 * (multiplicative decrease). Requests above the limit are rejected immediately (HTTP 503 + Retry-After) instead of
 * queueing, so an overloaded service stays fast for the requests it accepts. Lock-free; O(1) per request.
 */
public final class AdaptiveLimiter {
    private final int minLimit;
    private final int maxLimit;
    private final long targetNanos;
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicLong limitBits;
    private final LongAdder rejected = new LongAdder();

    public AdaptiveLimiter(int initial, int minLimit, int maxLimit, Duration targetLatency) {
        if (minLimit < 1 || maxLimit < minLimit || initial < minLimit || initial > maxLimit)
            throw new IllegalArgumentException("need 1 <= min <= initial <= max");
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.targetNanos = targetLatency.toNanos();
        this.limitBits = new AtomicLong(Double.doubleToLongBits(initial));
    }

    /** @return true if the request may proceed; it must then call {@link #release}. */
    public boolean tryAcquire() {
        int n = inflight.incrementAndGet();
        if (n > limit()) {
            inflight.decrementAndGet();
            rejected.increment();
            return false;
        }
        return true;
    }

    /** @param failed true for errors caused by overload (timeouts, 5xx from dependencies). */
    public void release(long latencyNanos, boolean failed) {
        inflight.decrementAndGet();
        boolean slow = failed || latencyNanos > targetNanos;
        long prev, next;
        do {
            prev = limitBits.get();
            double l = Double.longBitsToDouble(prev);
            double updated = slow ? Math.max(minLimit, l * 0.9) : Math.min(maxLimit, l + 1.0 / l);
            next = Double.doubleToLongBits(updated);
        } while (prev != next && !limitBits.compareAndSet(prev, next));
    }

    public int limit() { return (int) Double.longBitsToDouble(limitBits.get()); }
    public int inflight() { return inflight.get(); }
    public long rejected() { return rejected.sum(); }
}
