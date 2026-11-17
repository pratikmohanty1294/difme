package dev.difme.core.cmd.app;

import dev.difme.core.cmd.domain.PaymentDecider;
import dev.difme.core.cmd.domain.SubmitPayment;
import dev.difme.core.cmd.flags.Flags;
import dev.difme.core.cmd.idempotency.IdempotencyStore;
import dev.difme.core.cmd.persistence.PaymentEntity;
import dev.difme.core.cmd.persistence.PaymentRepository;
import dev.difme.core.cmd.ratelimit.RateLimitGuard;
import dev.difme.core.common.CommandContext;
import dev.difme.core.common.events.PaymentEvent;
import dev.difme.core.common.resilience.TxRetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Application service — the impure shell around the pure {@link PaymentDecider} (PDF §7.1 steps 3–7, 14; ADR-017).
 *
 * <ol>
 *   <li>Fast duplicate check (read only), so a client retry costs no rate-limit token.</li>
 *   <li>Rate limit: shared limiter behind timeout/circuit breaker, local degraded limit as fallback.</li>
 *   <li>One transaction: claim the idempotency key + write the payment + outbox event (all or nothing),
 *       retried on serialization conflicts.</li>
 *   <li>After commit: dispatch (saga start or direct ledger transfer). If that fails the payment stays ACCEPTED
 *       and {@link PaymentSweeper} finishes it; the client already has its answer.</li>
 * </ol>
 */
@ApplicationScoped
public class PaymentService {
    public record Outcome(UUID paymentId, String workflowId, String status) {}

    private record Accepted(PaymentEvent decided, UUID duplicateOf) {}

    @Inject IdempotencyStore idempotency;
    @Inject PaymentRepository repository;
    @Inject Flags flags;
    @Inject MeterRegistry metrics;
    @Inject RateLimitGuard rateLimit;
    @Inject Dispatcher dispatcher;
    Clock clock = Clock.systemUTC();

    public Outcome submit(SubmitPayment cmd, String traceId) {
        CommandContext ctx = new CommandContext(clock.instant(), ThreadLocalRandom.current().nextLong(), traceId);
        long nowMs = ctx.now().toEpochMilli();
        metrics.counter("difme_idempotency_checks_total").increment();

        Optional<UUID> seen = idempotency.find(cmd.idempotencyKey(), nowMs);
        if (seen.isPresent()) return duplicate(seen.get());

        if (!rateLimit.allow(cmd.debitAccountId().toString(), "payments", nowMs)) {
            return new Outcome(null, null, "REJECTED_RATE_LIMIT");
        }

        PaymentEvent decided = PaymentDecider.decide(cmd, ctx);
        Dispatcher.Mode mode = cmd.targetBank() != null || flags.useTemporalSaga(cmd.channel())
                ? Dispatcher.Mode.SAGA : Dispatcher.Mode.DIRECT;

        Accepted accepted;
        try {
            accepted = TxRetry.run(() -> QuarkusTransaction.requiringNew().call(() -> accept(cmd, decided, mode, traceId, nowMs)));
        } catch (RuntimeException e) {
            if (!TxRetry.isUniqueViolation(e)) throw e;
            // The key outlived its 24h dedup window but the payment it created still exists.
            UUID original = QuarkusTransaction.requiringNew().call(() ->
                    PaymentEntity.<PaymentEntity>find("idempotencyKey", cmd.idempotencyKey()).firstResult().paymentId);
            return duplicate(original);
        }
        if (accepted.duplicateOf() != null) return duplicate(accepted.duplicateOf());

        if (decided instanceof PaymentEvent.PaymentRejected r) {
            metrics.counter("difme_payments_total", "outcome", "rejected", "channel", cmd.channel()).increment();
            return new Outcome(r.paymentId(), null, "REJECTED");
        }

        dispatcher.dispatch(new Dispatcher.Job(decided.paymentId(), cmd.idempotencyKey(), cmd.debitAccountId(),
                cmd.creditAccountId(), cmd.amount().amountMinor(), cmd.amount().currency(), cmd.targetBank(), mode, traceId));
        metrics.counter("difme_payments_total", "outcome", "accepted", "channel", cmd.channel()).increment();
        return new Outcome(decided.paymentId(), decided.paymentId().toString(), "ACCEPTED");
    }

    /** Runs inside one transaction: key claim, aggregate and outbox commit together or not at all. */
    private Accepted accept(SubmitPayment cmd, PaymentEvent decided, Dispatcher.Mode mode, String traceId, long nowMs) {
        Optional<UUID> duplicate = idempotency.claim(cmd.idempotencyKey(), decided.paymentId(), nowMs);
        if (duplicate.isPresent()) return new Accepted(decided, duplicate.get());
        PaymentEntity p = repository.record(decided, traceId);
        if (p != null && decided instanceof PaymentEvent.PaymentInitiated) p.dispatch = mode.name();
        return new Accepted(decided, null);
    }

    private Outcome duplicate(UUID original) {
        metrics.counter("difme_idempotency_collisions_total").increment();
        return new Outcome(original, original.toString(), "DUPLICATE");
    }
}
