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
        return record(event, traceId, null);
    }

    /**
     * @param dispatch how a newly initiated payment will be dispatched; set before the INSERT so it is written in the
     *     same statement. (Setting it after persist() made Hibernate issue a second, full-row UPDATE per payment.)
     */
    @Transactional
    @ConsistencyProfile(Consistency.SERIALIZABLE)
    public PaymentEntity record(PaymentEvent event, String traceId, String dispatch) {
        // A new payment's id is a fresh UUIDv7: looking it up first was one wasted SELECT per payment (measured).
        PaymentEntity p = event instanceof PaymentInitiated ? null : PaymentEntity.findById(event.paymentId());
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
            p.dispatch = dispatch;
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

    /** Marks sagas as started, many payments per statement (bulk update: no version bump, so it never conflicts with
     *  the running workflow). Called by DispatchMarker with batches of up to 500 ids. */
    @Transactional
    public int markDispatched(java.util.Collection<java.util.UUID> paymentIds, java.time.Instant at) {
        return PaymentEntity.update("dispatchedAt = ?1 where paymentId in ?2 and dispatchedAt is null", at, paymentIds);
    }

    private String toJson(PaymentEvent e) {
        try {
            return json.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
