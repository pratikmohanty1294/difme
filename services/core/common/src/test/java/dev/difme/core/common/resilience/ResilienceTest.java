package dev.difme.core.common.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ResilienceTest {
    private static final Backoff NO_WAIT = new Backoff(Duration.ZERO, Duration.ZERO);

    @Test
    void backoffCeilingDoublesUpToCap() {
        Backoff b = new Backoff(Duration.ofMillis(10), Duration.ofMillis(100));
        assertEquals(Duration.ofMillis(10).toNanos(), b.ceilingNanos(0));
        assertEquals(Duration.ofMillis(40).toNanos(), b.ceilingNanos(2));
        assertEquals(Duration.ofMillis(100).toNanos(), b.ceilingNanos(10));
        assertEquals(Duration.ofMillis(100).toNanos(), b.ceilingNanos(64));     // no overflow
        for (int i = 0; i < 1000; i++) assertTrue(b.delayNanos(3) <= b.ceilingNanos(3));
    }

    @Test
    void txRetryRetriesSerializationConflictsThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String r = TxRetry.run(() -> {
            if (calls.incrementAndGet() < 3) throw new RuntimeException(new SQLException("conflict", "40001"));
            return "ok";
        }, 5, NO_WAIT);
        assertEquals("ok", r);
        assertEquals(3, calls.get());
    }

    @Test
    void txRetryDoesNotRetryOtherErrorsAndStopsAtLimit() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> TxRetry.run(() -> {
            calls.incrementAndGet();
            throw new RuntimeException(new SQLException("dup", "23505"));
        }, 5, NO_WAIT));
        assertEquals(1, calls.get());

        calls.set(0);
        assertThrows(RuntimeException.class, () -> TxRetry.run(() -> {
            calls.incrementAndGet();
            throw new SQLException("deadlock", "40P01");
        }, 4, NO_WAIT));
        assertEquals(4, calls.get());
        assertTrue(TxRetry.isUniqueViolation(new IllegalStateException(new SQLException("dup", "23505"))));
    }

    @Test
    void limiterShedsAboveLimitAndAdapts() {
        AdaptiveLimiter l = new AdaptiveLimiter(2, 1, 100, Duration.ofMillis(50));
        assertTrue(l.tryAcquire());
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());                           // shed, not queued
        assertEquals(1, l.rejected());
        l.release(Duration.ofMillis(5).toNanos(), false);
        l.release(Duration.ofMillis(5).toNanos(), false);
        for (int i = 0; i < 200; i++) { assertTrue(l.tryAcquire()); l.release(Duration.ofMillis(1).toNanos(), false); }
        int grown = l.limit();
        assertTrue(grown > 2, "fast responses grow the limit: " + grown);
        for (int i = 0; i < 50; i++) { l.tryAcquire(); l.release(Duration.ofMillis(500).toNanos(), false); }
        assertTrue(l.limit() < grown, "slow responses shrink it");
        assertTrue(l.limit() >= 1);
        assertEquals(0, l.inflight());
    }

    @Test
    void tokenBucketRefillsAtRateAndCapsBurst() {
        AtomicLong now = new AtomicLong();
        TokenBucket b = new TokenBucket(10, 2, now::get);       // 10/s, burst 2
        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire());
        now.addAndGet(100_000_000);                            // 100 ms → 1 token
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire());
        now.addAndGet(10_000_000_000L);                        // long idle → capped at burst
        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire());
    }
}
