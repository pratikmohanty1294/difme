package dev.difme.core.cmd.app;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AsyncDispatcherTest {
    /** Records dispatched payments instead of calling Temporal / the ledger. */
    static class FakeDispatcher extends Dispatcher {
        final Set<UUID> dispatched = ConcurrentHashMap.newKeySet();
        volatile CountDownLatch gate;          // when set, dispatch blocks until released
        volatile UUID failOn;

        @Override
        public boolean dispatch(Job job) {
            try {
                if (gate != null) gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (job.paymentId().equals(failOn)) throw new IllegalStateException("temporal down");
            dispatched.add(job.paymentId());
            return true;
        }
    }

    static Dispatcher.Job job(UUID id) {
        return new Dispatcher.Job(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 100, "INR", null,
                Dispatcher.Mode.SAGA, "t");
    }

    static AsyncDispatcher async(FakeDispatcher d, int workers, int capacity) {
        AsyncDispatcher a = new AsyncDispatcher();
        a.dispatcher = d;
        a.metrics = new SimpleMeterRegistry();
        a.workers = workers;
        a.capacity = capacity;
        a.start();
        return a;
    }

    static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(c.getAsBoolean(), "condition not reached within 5 s");
    }

    @Test
    void dispatchesEverySubmittedJob() throws Exception {
        FakeDispatcher d = new FakeDispatcher();
        AsyncDispatcher a = async(d, 4, 1024);
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 500; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            assertTrue(a.submit(job(id)));
        }
        await(() -> d.dispatched.containsAll(ids));
        a.stop();
    }

    @Test
    void fullQueueTellsTheCallerToDispatchInline() throws Exception {
        FakeDispatcher d = new FakeDispatcher();
        d.gate = new CountDownLatch(1);                    // the single worker blocks on its first job
        AsyncDispatcher a = async(d, 1, 2);
        assertTrue(a.submit(job(UUID.randomUUID())));      // taken by the worker
        await(() -> a.submit(job(UUID.randomUUID())) && a.submit(job(UUID.randomUUID())) || true);
        assertFalse(a.submit(job(UUID.randomUUID())), "a full queue must refuse, so the request dispatches inline");
        d.gate.countDown();
        a.stop();
    }

    @Test
    void aFailingDispatchDoesNotStopTheWorker() throws Exception {
        FakeDispatcher d = new FakeDispatcher();
        AsyncDispatcher a = async(d, 1, 16);
        UUID bad = UUID.randomUUID(), good = UUID.randomUUID();
        d.failOn = bad;
        a.submit(job(bad));
        a.submit(job(good));
        await(() -> d.dispatched.contains(good));
        assertFalse(d.dispatched.contains(bad));
        a.stop();
    }
}
