package dev.difme.core.cmd.app;

import dev.difme.core.cmd.persistence.PaymentRepository;
import dev.difme.core.common.resilience.TxRetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Records "saga started" for many payments in one UPDATE instead of one transaction each (the baseline measured
 * 4.6 connection checkouts per payment; this was one of them).
 *
 * <p>One thread blocks until an id arrives, then drains up to {@link #MAX_BATCH}. Under load the batches grow
 * by themselves; at low load an id is written at once. Losing a batch (crash, failed flush) is safe:
 * {@link PaymentSweeper} re-dispatches payments without {@code dispatchedAt}, and starting a saga is idempotent
 * (workflow id = payment id, REJECT_DUPLICATE).
 */
@Startup
@ApplicationScoped
public class DispatchMarker {
    static final int MAX_BATCH = 500;
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(DispatchMarker.class);

    @Inject PaymentRepository repository;
    @Inject MeterRegistry metrics;
    Clock clock = Clock.systemUTC();
    /** Transaction boundary; replaced in unit tests. */
    java.util.function.Consumer<Runnable> inNewTx = work -> QuarkusTransaction.requiringNew().run(work);

    private final LinkedBlockingQueue<UUID> pending = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private Thread worker;

    @PostConstruct
    void start() {
        metrics.gaugeCollectionSize("difme_dispatch_mark_pending", List.of(), pending);
        worker = Thread.ofVirtual().name("dispatch-marker").start(this::loop);
    }

    /** Queues the mark; returns immediately. */
    public void mark(UUID paymentId) {
        pending.add(paymentId);
    }

    private void loop() {
        List<UUID> batch = new ArrayList<>(MAX_BATCH);
        while (running) {
            try {
                batch.add(pending.take());
                pending.drainTo(batch, MAX_BATCH - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<UUID> ids) {
        try {
            TxRetry.run(() -> {
                inNewTx.accept(() -> repository.markDispatched(ids, clock.instant()));
                return null;
            });
            metrics.summary("difme_dispatch_mark_batch_size").record(ids.size());
        } catch (RuntimeException e) {
            // Not lost for good: the sweeper re-dispatches these payments, idempotently.
            metrics.counter("difme_dispatch_mark_failed_total").increment(ids.size());
            LOG.warnf("marking %d payments as dispatched failed; the sweeper will retry them: %s", ids.size(), e.toString());
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        List<UUID> rest = new ArrayList<>();
        pending.drainTo(rest);
        if (!rest.isEmpty()) flush(rest);
    }
}
