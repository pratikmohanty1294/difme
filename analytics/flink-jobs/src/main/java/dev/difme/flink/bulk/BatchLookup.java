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
    private Map<K, List<CompletableFuture<V>>> pending = new HashMap<>();

    public BatchLookup(Function<Collection<K>, Map<K, V>> fetch, int maxBatch, long maxWaitMs) {
        this.fetch = fetch;
        this.maxBatch = maxBatch;
        worker.scheduleWithFixedDelay(this::flush, maxWaitMs, maxWaitMs, TimeUnit.MILLISECONDS);
    }

    public synchronized CompletableFuture<V> get(K key) {
        CompletableFuture<V> f = new CompletableFuture<>();
        pending.computeIfAbsent(key, k -> new ArrayList<>(1)).add(f);
        if (pending.size() >= maxBatch) worker.execute(this::flush);
        return f;
    }

    private void flush() {
        Map<K, List<CompletableFuture<V>>> batch;
        synchronized (this) {
            if (pending.isEmpty()) return;
            batch = pending;
            pending = new HashMap<>();
        }
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
