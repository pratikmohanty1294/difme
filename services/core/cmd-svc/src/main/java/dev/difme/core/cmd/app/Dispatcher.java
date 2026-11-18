package dev.difme.core.cmd.app;

import dev.difme.core.cmd.ledger.LedgerClient;
import dev.difme.core.cmd.persistence.PaymentEntity;
import dev.difme.core.cmd.persistence.PaymentRepository;
import dev.difme.core.common.StepIds;
import dev.difme.core.common.Uuid7;
import dev.difme.core.common.events.PaymentEvent;
import dev.difme.core.common.resilience.TxRetry;
import dev.difme.core.common.saga.TransferInput;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Runs the side effects of an accepted payment after its transaction has committed (ADR-017). Called inline right
 * after acceptance and again by {@link PaymentSweeper} for payments whose dispatch did not finish (crash, timeout,
 * open circuit). Every step is idempotent, so running it twice is harmless.
 */
@ApplicationScoped
public class Dispatcher {
    public enum Mode { SAGA, DIRECT }

    /** Everything needed to (re)dispatch a payment, read from the committed row. */
    public record Job(UUID paymentId, UUID idempotencyKey, UUID debit, UUID credit, long amountMinor, String currency,
                      String targetBank, Mode mode, String traceId) {
        static Job of(PaymentEntity p, String traceId) {
            return new Job(p.paymentId, p.idempotencyKey, p.debitAccountId, p.creditAccountId, p.amountMinor,
                    p.currency, p.targetBank, Mode.valueOf(p.dispatch), traceId);
        }
    }

    @Inject GuardedCalls calls;
    @Inject PaymentRepository repository;
    @Inject DispatchMarker marker;
    @Inject MeterRegistry metrics;
    Clock clock = Clock.systemUTC();

    /** @return true if dispatch finished; false if deferred to the sweeper. Never throws. */
    public boolean dispatch(Job job) {
        try {
            if (job.mode() == Mode.SAGA) {
                calls.startSaga(new TransferInput(job.paymentId().toString(), job.idempotencyKey().toString(),
                        job.debit().toString(), job.credit().toString(), job.amountMinor(), job.currency(),
                        job.targetBank(), job.traceId()));
                marker.mark(job.paymentId());   // batched; a lost mark only means an idempotent re-dispatch
            } else {
                try {
                    String tb = calls.directTransfer(job.paymentId(), job.debit(), job.credit(), job.amountMinor());
                    record(new PaymentEvent.PaymentCompleted(eventId(job, "done"), job.paymentId(), now(),
                            job.paymentId().toString(), tb), job.traceId());
                } catch (LedgerClient.LedgerRejected rejected) {
                    record(new PaymentEvent.PaymentRejected(eventId(job, "rejected"), job.paymentId(), now(),
                            rejected.getMessage()), job.traceId());
                }
            }
            return true;
        } catch (RuntimeException e) {
            metrics.counter("difme_dispatch_deferred_total", "mode", job.mode().name()).increment();
            Log.warnf("dispatch of %s deferred to sweeper: %s", job.paymentId(), e.toString());
            return false;
        }
    }

    private void record(PaymentEvent event, String traceId) {
        inTx(() -> repository.record(event, traceId));
    }

    private static void inTx(Runnable work) {
        TxRetry.run(() -> { QuarkusTransaction.requiringNew().run(work); return null; });
    }

    private UUID eventId(Job job, String step) {
        return Uuid7.derived(now(), StepIds.of(job.paymentId(), step).getLeastSignificantBits());
    }

    private Instant now() { return clock.instant(); }
}
