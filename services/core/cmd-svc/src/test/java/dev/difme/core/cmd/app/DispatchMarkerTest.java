package dev.difme.core.cmd.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.difme.core.cmd.persistence.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class DispatchMarkerTest {

    /** Records batches instead of touching a database. */
    static final class FakeRepo extends PaymentRepository {
        final Set<UUID> marked = ConcurrentHashMap.newKeySet();
        final List<Integer> batches = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override
        public int markDispatched(Collection<UUID> ids, Instant at) {
            if (fail) throw new IllegalStateException("db down");
            batches.add(ids.size());
            marked.addAll(ids);
            return ids.size();
        }
    }

    private static DispatchMarker marker(FakeRepo repo) {
        DispatchMarker m = new DispatchMarker();
        m.repository = repo;
        m.metrics = new SimpleMeterRegistry();
        m.inNewTx = Runnable::run;
        m.start();
        return m;
    }

    @Test
    void marksEveryIdInBoundedBatches() throws Exception {
        FakeRepo repo = new FakeRepo();
        DispatchMarker m = marker(repo);
        for (int i = 0; i < 2_000; i++) m.mark(UUID.randomUUID());
        for (int i = 0; i < 200 && repo.marked.size() < 2_000; i++) Thread.sleep(10);
        m.stop();
        assertEquals(2_000, repo.marked.size(), "every queued id is written exactly once");
        assertTrue(repo.batches.stream().allMatch(b -> b <= DispatchMarker.MAX_BATCH), repo.batches.toString());
        assertTrue(repo.batches.size() < 2_000, "ids are batched, not written one by one: " + repo.batches.size());
    }

    @Test
    void failedFlushIsLeftToTheSweeperAndTheWorkerKeepsGoing() throws Exception {
        FakeRepo repo = new FakeRepo();
        DispatchMarker m = marker(repo);
        repo.fail = true;
        m.mark(UUID.randomUUID());
        Thread.sleep(100);
        repo.fail = false;
        UUID later = UUID.randomUUID();
        m.mark(later);
        for (int i = 0; i < 100 && !repo.marked.contains(later); i++) Thread.sleep(10);
        m.stop();
        assertTrue(repo.marked.contains(later), "worker survived the failed flush");
        assertEquals(1, repo.marked.size(), "the failed id is not retried here; the sweeper owns recovery");
    }
}
