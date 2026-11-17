package dev.difme.core.cmd.app;

import dev.difme.core.cmd.persistence.PaymentEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Recovery for the gap between "payment committed" and "side effect done" (ADR-017): accepted payments whose dispatch
 * never completed (process crash, timeout, open circuit) are dispatched again. Safe with several replicas running it
 * at once, because every dispatch step is idempotent. Exposes the backlog as a metric for alerting.
 */
@ApplicationScoped
public class PaymentSweeper {
    @Inject Dispatcher dispatcher;
    @Inject MeterRegistry metrics;
    @ConfigProperty(name = "difme.sweeper.stale-after", defaultValue = "PT30S") Duration staleAfter;
    @ConfigProperty(name = "difme.sweeper.batch", defaultValue = "100") int batch;
    private final AtomicInteger backlog = new AtomicInteger();

    @PostConstruct
    void registerGauge() {
        metrics.gauge("difme_payments_undispatched", backlog);   // gauge keeps a weak ref: hold the AtomicInteger
    }

    @Scheduled(every = "${difme.sweeper.every:15s}", delayed = "30s",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        Instant cutoff = Instant.now().minus(staleAfter);
        List<PaymentEntity> stuck = QuarkusTransaction.requiringNew().call(() ->
                PaymentEntity.<PaymentEntity>find(
                        "status = 'ACCEPTED' and dispatch is not null and dispatchedAt is null and updatedAt < ?1 order by updatedAt",
                        cutoff).page(0, batch).list());
        backlog.set(stuck.size());
        for (PaymentEntity p : stuck) {
            boolean done = dispatcher.dispatch(Dispatcher.Job.of(p, null));
            metrics.counter("difme_sweeper_redispatch_total", "result", done ? "done" : "deferred").increment();
        }
    }
}
