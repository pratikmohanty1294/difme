package dev.difme.flink.bulk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BatchLookupTest {
    @Test
    void coalescesKeysIntoFewQueries() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        try (BatchLookup<String, String> lookup = new BatchLookup<>(keys -> {
            queries.incrementAndGet();
            Map<String, String> m = new HashMap<>();
            keys.forEach(k -> m.put(k, "v" + k));
            return m;
        }, 1000, 5)) {
            List<CompletableFuture<String>> fs = IntStream.range(0, 2500).mapToObj(i -> lookup.get("k" + i)).toList();
            CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).get();
            assertEquals("vk42", fs.get(42).get());
            assertTrue(queries.get() <= 4, "2500 lookups should need ~3 batched queries, took " + queries.get());
        }
    }
}
