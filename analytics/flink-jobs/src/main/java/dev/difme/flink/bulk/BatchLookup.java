package dev.difme.flink.bulk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Coalesces individual key lookups into batched queries (≈1,000 keys per {@code IN}/{@code ANY} query) so database
 * validation never costs a round-trip per record (ADR-011). Flushes when the batch is full or after maxWaitMs.
 */
public final class BatchLookup<K, V> implements AutoCloseable {
    private final Function<Collection<K>, Map<K, V>> fetch;
    private final int maxBatch;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "batch-lookup");
        t.setDaemon(true);
        return t;
    });
    private final long maxWaitNanos;
    private Map<K, List<CompletableFuture<V>>> pending = new HashMap<>();
    private long firstPendingAt;

    public BatchLookup(Function<Collection<K>, Map<K, V>> fetch, int maxBatch, long maxWaitMs) {
        this.fetch = fetch;
        this.maxBatch = maxBatch;
        this.maxWaitNanos = TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        worker.scheduleWithFixedDelay(this::flushIfStale, maxWaitMs, Math.max(1, maxWaitMs / 2), TimeUnit.MILLISECONDS);
    }

    /** Full batches are cut immediately and queried as one statement; partial ones wait at most maxWaitMs. */
    public CompletableFuture<V> get(K key) {
        CompletableFuture<V> f = new CompletableFuture<>();
        Map<K, List<CompletableFuture<V>>> full = null;
        synchronized (this) {
            if (pending.isEmpty()) firstPendingAt = System.nanoTime();
            pending.computeIfAbsent(key, k -> new ArrayList<>(1)).add(f);
            if (pending.size() >= maxBatch) { full = pending; pending = new HashMap<>(); }
        }
        if (full != null) {
            Map<K, List<CompletableFuture<V>>> batch = full;
            worker.execute(() -> run(batch));
        }
        return f;
    }

    private void flushIfStale() {
        Map<K, List<CompletableFuture<V>>> batch;
        synchronized (this) {
            if (pending.isEmpty() || System.nanoTime() - firstPendingAt < maxWaitNanos) return;
            batch = pending;
            pending = new HashMap<>();
        }
        run(batch);
    }

    private void flush() {
        Map<K, List<CompletableFuture<V>>> batch;
        synchronized (this) {
            if (pending.isEmpty()) return;
            batch = pending;
            pending = new HashMap<>();
        }
        run(batch);
    }

    private void run(Map<K, List<CompletableFuture<V>>> batch) {
        try {
            Map<K, V> found = fetch.apply(batch.keySet());
            batch.forEach((k, fs) -> fs.forEach(f -> f.complete(found.get(k))));
        } catch (Throwable t) {
            batch.values().forEach(fs -> fs.forEach(f -> f.completeExceptionally(t)));
        }
    }

    @Override
    public void close() {
        flush();
        worker.shutdown();
    }
}
