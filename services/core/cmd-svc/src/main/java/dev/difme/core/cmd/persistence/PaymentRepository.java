package dev.difme.core.cmd.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.difme.core.common.events.PaymentEvent;
import dev.difme.core.common.events.PaymentEvent.PaymentInitiated;
import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.ConsistencyProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** Persists the aggregate + outbox atomically, then signals the after-commit L2 write-through. */
@ApplicationScoped
public class PaymentRepository {
    @Inject ObjectMapper json;
    @Inject Event<PaymentCommitted> committed;

    @Transactional
    @ConsistencyProfile(Consistency.SERIALIZABLE)
    public PaymentEntity record(PaymentEvent event, String traceId) {
        PaymentEntity p = PaymentEntity.findById(event.paymentId());
        if (event instanceof PaymentInitiated e) {
            p = new PaymentEntity();
            p.paymentId = e.paymentId();
            p.idempotencyKey = e.idempotencyKey();
            p.debitAccountId = e.debitAccountId();
            p.creditAccountId = e.creditAccountId();
            p.amountMinor = e.amount().amountMinor();
            p.currency = e.amount().currency();
            p.channel = e.channel();
            p.targetBank = e.targetBank();
            p.status = "ACCEPTED";
            p.createdAt = e.occurredAt();
            p.updatedAt = e.occurredAt();
            p.persist();
        } else if (p != null) {
            switch (event) {
                case PaymentEvent.PaymentCompleted c -> { p.status = "COMPLETED"; p.workflowId = c.workflowId(); p.ledgerTransferId = c.ledgerTransferId(); }
                case PaymentEvent.PaymentCompensated c -> { p.status = "COMPENSATED"; p.workflowId = c.workflowId(); }
                case PaymentEvent.PaymentRejected r -> p.status = "REJECTED";
                default -> { }
            }
            p.updatedAt = event.occurredAt();
        }
        OutboxEntity o = new OutboxEntity();
        o.id = event.eventId();
        o.aggregateType = "payments";
        o.aggregateId = event.paymentId();
        o.type = PaymentEvent.typeOf(event);
        o.payload = toJson(event);
        o.traceId = traceId;
        o.eventTimestamp = event.occurredAt();
        o.persist();
        committed.fire(new PaymentCommitted(event));
        return p;
    }

    private String toJson(PaymentEvent e) {
        try {
            return json.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
