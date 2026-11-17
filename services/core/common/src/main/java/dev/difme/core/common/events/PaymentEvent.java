package dev.difme.core.common.events;

import dev.difme.core.common.Money;
import java.time.Instant;
import java.util.UUID;

/** Domain events written to the outbox and published as CloudEvents (api/asyncapi). */
public sealed interface PaymentEvent {
    UUID eventId();
    UUID paymentId();
    Instant occurredAt();

    record PaymentInitiated(UUID eventId, UUID paymentId, Instant occurredAt, UUID idempotencyKey,
                            UUID debitAccountId, UUID creditAccountId, Money amount, String channel,
                            Double edgeFraudScore, String targetBank) implements PaymentEvent {}

    record PaymentCompleted(UUID eventId, UUID paymentId, Instant occurredAt, String workflowId,
                            String ledgerTransferId) implements PaymentEvent {}

    record PaymentCompensated(UUID eventId, UUID paymentId, Instant occurredAt, String workflowId,
                              String failedStep, String reason) implements PaymentEvent {}

    record PaymentRejected(UUID eventId, UUID paymentId, Instant occurredAt, String reason) implements PaymentEvent {}

    static String typeOf(PaymentEvent e) {
        return "dev.difme.payments." + e.getClass().getSimpleName() + ".v1";
    }
}
