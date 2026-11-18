package dev.difme.core.cmd.app;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Takes dispatch (saga start or direct ledger transfer) off the request path. {@link PaymentService} answers 202 as
 * soon as the accept transaction commits, which is what its contract already said ("the client already has its
 * answer"); before this, the request waited for the dispatch inline (measured: Temporal start ~160 ms of each accepted
 * request under load), holding a worker thread and a slot of the adaptive limiter.
 *
 * <p>Correctness is unchanged: the payment is durable and ACCEPTED at commit, dispatch is idempotent (workflow id /
 * ledger transfer id = payment id), and {@link PaymentSweeper} re-dispatches anything not marked dispatched, so a job
 * lost in this queue (crash, shutdown) is recovered. A bounded queue gives backpressure: when it is full the caller
 * dispatches inline, as before, rather than queueing without limit.
 */
@Startup
@ApplicationScoped
public class AsyncDispatcher {
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(AsyncDispatcher.class);

    @Inject Dispatcher dispatcher;
    @Inject MeterRegistry metrics;
    @ConfigProperty(name = "difme.dispatch.async.workers", defaultValue = "8") int workers;
    @ConfigProperty(name = "difme.dispatch.async.queue", defaultValue = "1024") int capacity;

    private BlockingQueue<Dispatcher.Job> queue;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;

    @PostConstruct
    void start() {
        queue = new ArrayBlockingQueue<>(capacity);
        metrics.gaugeCollectionSize("difme_dispatch_async_pending", List.of(), queue);
        for (int i = 0; i < workers; i++) {
            threads.add(Thread.ofVirtual().name("dispatch-" + i).start(this::loop));
        }
    }

    /** Queues the job; false when the queue is full (the caller then dispatches inline). */
    public boolean submit(Dispatcher.Job job) {
        boolean queued = queue.offer(job);
        if (!queued) metrics.counter("difme_dispatch_async_inline_total").increment();
        return queued;
    }

    int pending() {
        return queue.size();
    }

    private void loop() {
        while (running) {
            try {
                Dispatcher.Job job = queue.take();
                dispatcher.dispatch(job);   // failures are logged/counted there and left to the sweeper
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.warnf("async dispatch failed; the sweeper will retry: %s", e.toString());
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        // Jobs still queued are not lost: their payments are ACCEPTED and undispatched, so the sweeper picks them up.
        if (!queue.isEmpty()) LOG.infof("%d queued dispatches left to the sweeper at shutdown", queue.size());
    }
}
