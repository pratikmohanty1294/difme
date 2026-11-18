package dev.difme.core.common.resilience;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Retries a whole database transaction when the database aborts it for a transient conflict (ADR-017).
 * YugabyteDB at SERIALIZABLE isolation reports read/write conflicts as SQLSTATE 40001 and expects the client to
 * retry; 40P01 is a deadlock. The unit of work must be a complete transaction (begin..commit) so a retry re-reads
 * everything. Any other error, including unique violations (23505), is not retried.
 */
public final class TxRetry {
    static final Set<String> RETRYABLE = Set.of("40001", "40P01");
    public static final int DEFAULT_ATTEMPTS = 5;
    // Process-wide counters (no metrics dependency in common); services expose them as gauges.
    private static final java.util.concurrent.atomic.LongAdder RETRIES = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder EXHAUSTED = new java.util.concurrent.atomic.LongAdder();

    private TxRetry() {}

    public static <T> T run(Callable<T> transaction) {
        return run(transaction, DEFAULT_ATTEMPTS, Backoff.DEFAULT);
    }

    public static <T> T run(Callable<T> transaction, int maxAttempts, Backoff backoff) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.call();
            } catch (Exception e) {
                boolean transientError = isTransient(e);
                if (attempt + 1 >= maxAttempts || !transientError) {
                    if (transientError) EXHAUSTED.increment();
                    throw rethrow(e);
                }
                RETRIES.increment();
                backoff.sleep(attempt);
            }
        }
    }

    /** Transient conflicts retried so far (each retry also sleeps a backoff). */
    public static long retries() { return RETRIES.sum(); }

    /** Transactions that still conflicted after the last attempt. */
    public static long exhausted() { return EXHAUSTED.sum(); }

    /** True if any cause in the chain is a SQL error with a retryable SQLSTATE. */
    public static boolean isTransient(Throwable t) {
        return sqlState(t) != null && RETRYABLE.contains(sqlState(t));
    }

    /** True if any cause is a unique-constraint violation (23505). */
    public static boolean isUniqueViolation(Throwable t) {
        return "23505".equals(sqlState(t));
    }

    static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s && s.getSQLState() != null) return s.getSQLState();
            if (c.getCause() == c) break;
        }
        return null;
    }

    private static RuntimeException rethrow(Exception e) {
        return e instanceof RuntimeException r ? r : new IllegalStateException(e);
    }
}
