package dev.difme.core.cmd.app;

import dev.difme.core.cmd.domain.PaymentDecider;
import dev.difme.core.cmd.domain.SubmitPayment;
import dev.difme.core.cmd.flags.Flags;
import dev.difme.core.cmd.idempotency.IdempotencyStore;
import dev.difme.core.cmd.ledger.LedgerClient;
import dev.difme.core.cmd.persistence.PaymentRepository;
import dev.difme.core.cmd.ratelimit.RateLimiterClient;
import dev.difme.core.common.CommandContext;
import dev.difme.core.common.Uuid7;
import dev.difme.core.common.events.PaymentEvent;
import dev.difme.core.common.saga.PaymentWorkflow;
import dev.difme.core.common.saga.TransferInput;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Application service — the impure shell around the pure {@link PaymentDecider} (PDF §7.1 steps 3–7, 14).
 * Time and randomness are captured once here and passed in.
 */
@ApplicationScoped
public class PaymentService {
    public record Outcome(UUID paymentId, String workflowId, String status) {}

    @Inject IdempotencyStore idempotency;
    @Inject PaymentRepository repository;
    @Inject LedgerClient ledger;
    @Inject Flags flags;
    @Inject WorkflowClient temporal;
    @Inject MeterRegistry metrics;
    @Inject @RestClient RateLimiterClient rateLimiter;
    Clock clock = Clock.systemUTC();

    public Outcome submit(SubmitPayment cmd, String traceId) {
        CommandContext ctx = new CommandContext(clock.instant(), ThreadLocalRandom.current().nextLong(), traceId);
        metrics.counter("difme_idempotency_checks_total").increment();

        RateLimiterClient.Decision rl = rateLimiter.acquire(
                new RateLimiterClient.Acquire(cmd.debitAccountId().toString(), "payments", ctx.now().toEpochMilli()));
        if (!rl.allowed()) {
            return new Outcome(null, null, "REJECTED_RATE_LIMIT");
        }

        PaymentEvent decided = PaymentDecider.decide(cmd, ctx);
        Optional<UUID> duplicate = idempotency.claim(cmd.idempotencyKey(), decided.paymentId(), ctx.now().toEpochMilli());
        if (duplicate.isPresent()) {
            metrics.counter("difme_idempotency_collisions_total").increment();
            return new Outcome(duplicate.get(), duplicate.get().toString(), "DUPLICATE");
        }

        repository.record(decided, traceId);
        if (decided instanceof PaymentEvent.PaymentRejected r) {
            metrics.counter("difme_payments_total", "outcome", "rejected", "channel", cmd.channel()).increment();
            return new Outcome(r.paymentId(), null, "REJECTED");
        }

        String workflowId = decided.paymentId().toString();
        TransferInput input = new TransferInput(workflowId, cmd.idempotencyKey().toString(),
                cmd.debitAccountId().toString(), cmd.creditAccountId().toString(), cmd.amount().amountMinor(),
                cmd.amount().currency(), cmd.targetBank(), traceId);

        if (cmd.targetBank() != null || flags.useTemporalSaga(cmd.channel())) {
            PaymentWorkflow wf = temporal.newWorkflowStub(PaymentWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)                       // Temporal dedups on workflow id too
                    .setTaskQueue(PaymentWorkflow.TASK_QUEUE)
                    .build());
            WorkflowClient.start(wf::run, input);
        } else {
            // Same-bank fast path (flag off): direct ledger transfer, then completion event.
            UUID transferId = Uuid7.derived(ctx.now(), ctx.seed() + 7);
            String tb = ledger.direct(transferId, cmd.debitAccountId(), cmd.creditAccountId(), cmd.amount().amountMinor());
            repository.record(new PaymentEvent.PaymentCompleted(ctx.newId(2), decided.paymentId(), ctx.now(), workflowId, tb), traceId);
        }
        metrics.counter("difme_payments_total", "outcome", "accepted", "channel", cmd.channel()).increment();
        return new Outcome(decided.paymentId(), workflowId, "ACCEPTED");
    }
}
