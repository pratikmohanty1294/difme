package dev.difme.core.cmd.saga;

import dev.difme.core.cmd.ledger.LedgerClient;
import dev.difme.core.cmd.persistence.PaymentRepository;
import dev.difme.core.common.Uuid7;
import dev.difme.core.common.events.PaymentEvent;
import dev.difme.core.common.saga.PaymentActivities;
import dev.difme.core.common.saga.TransferInput;
import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.ConsistencyProfile;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Saga step implementations (PDF §7.2). Each step's side effect is keyed by (paymentId, step):
 * TigerBeetle transfer ids are name-based UUIDs and SQL writes are upserts — retries are no-ops.
 */
@ApplicationScoped
public class PaymentActivitiesImpl implements PaymentActivities {
    @Inject LedgerClient ledger;
    @Inject PaymentRepository repository;
    @Inject AgroalDataSource db;
    @Inject io.micrometer.core.instrument.MeterRegistry metrics;
    static final java.util.List<String> NOTHING_TO_REFUND =
            java.util.List.of("PendingTransferNotFound", "PendingTransferAlreadyVoided", "PendingTransferExpired");
    Clock clock = Clock.systemUTC();

    static UUID stepId(String paymentId, String step) {
        return dev.difme.core.common.StepIds.of(paymentId, step);
    }

    @Override
    public String debitSource(TransferInput in) {
        return ledger.pending(stepId(in.paymentId(), "debit"), UUID.fromString(in.debitAccountId()),
                UUID.fromString(in.creditAccountId()), in.amountMinor());
    }

    @Override
    @ConsistencyProfile(Consistency.SERIALIZABLE)
    public String reserveSettlement(TransferInput in) {
        UUID id = stepId(in.paymentId(), "reserve");
        exec("INSERT INTO settlement.reservation (reservation_id, payment_id, bank_pair, amount_minor, status, updated_at) "
                + "VALUES (?, ?::uuid, ?, ?, 'RESERVED', ?) ON CONFLICT (payment_id) DO NOTHING",
                id, in.paymentId(), "self->" + in.targetBank(), in.amountMinor(), Timestamp.from(clock.instant()));
        return id.toString();
    }

    @Override
    public void notifyTargetBank(TransferInput in) {
        // Settlement instruction row is the durable record; the bank gateway adapter (UPI/ISO 20022) reads it via CDC.
        exec("INSERT INTO settlement.instruction (instruction_id, payment_id, target_bank, status, sent_at) "
                + "VALUES (?, ?::uuid, ?, 'SENT', ?) ON CONFLICT (payment_id) DO NOTHING",
                stepId(in.paymentId(), "notify"), in.paymentId(), String.valueOf(in.targetBank()), Timestamp.from(clock.instant()));
    }

    @Override
    public String creditBeneficiary(TransferInput in) {
        return ledger.postPending(stepId(in.paymentId(), "credit"), stepId(in.paymentId(), "debit"));
    }

    @Override
    @ConsistencyProfile(Consistency.SERIALIZABLE)
    public void releaseSettlement(TransferInput in) {
        exec("UPDATE settlement.reservation SET status = 'RELEASED', updated_at = ? WHERE payment_id = ?::uuid AND status = 'RESERVED'",
                Timestamp.from(clock.instant()), in.paymentId());
    }

    @Override
    public void markCompleted(TransferInput in, String ledgerTransferId) {
        Instant now = clock.instant();
        repository.record(new PaymentEvent.PaymentCompleted(Uuid7.derived(now, stepId(in.paymentId(), "done").getLeastSignificantBits()),
                UUID.fromString(in.paymentId()), now, in.paymentId(), ledgerTransferId), in.traceId());
    }

    @Override
    public void refundSource(TransferInput in) {
        try {
            ledger.voidPending(stepId(in.paymentId(), "refund"), stepId(in.paymentId(), "debit"));
        } catch (LedgerClient.LedgerRejected e) {
            // Nothing left to refund: the pending debit never existed, was already voided, or expired (TigerBeetle
            // releases expired pending amounts itself). Anything else is retried by Temporal until it succeeds.
            if (NOTHING_TO_REFUND.stream().noneMatch(e.getMessage()::contains)) {
                compensationFailed("refund");
                throw e;
            }
        } catch (RuntimeException e) {
            compensationFailed("refund");
            throw e;
        }
    }

    @Override
    @ConsistencyProfile(Consistency.SERIALIZABLE)
    public void cancelReservation(TransferInput in) {
        try {
            cancelReservationRow(in);
        } catch (RuntimeException e) {
            compensationFailed("cancel-reservation");
            throw e;
        }
    }

    private void compensationFailed(String step) {
        metrics.counter("difme_compensation_failures_total", "step", step).increment();
    }

    private void cancelReservationRow(TransferInput in) {
        exec("UPDATE settlement.reservation SET status = 'COMPENSATED', updated_at = ? WHERE payment_id = ?::uuid AND status = 'RESERVED'",
                Timestamp.from(clock.instant()), in.paymentId());
    }

    @Override
    public void markCompensated(TransferInput in, String failedStep, String reason) {
        Instant now = clock.instant();
        repository.record(new PaymentEvent.PaymentCompensated(Uuid7.derived(now, stepId(in.paymentId(), "comp").getLeastSignificantBits()),
                UUID.fromString(in.paymentId()), now, in.paymentId(), failedStep, reason), in.traceId());
    }

    private void exec(String sql, Object... args) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
