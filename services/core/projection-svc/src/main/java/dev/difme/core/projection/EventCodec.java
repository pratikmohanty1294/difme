package dev.difme.core.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.difme.core.common.events.PaymentEvent;
import java.util.Map;

/** Maps CloudEvents type → domain event class. Shared shape with the outbox payload written by cmd-svc. */
public final class EventCodec {
    private static final Map<String, Class<? extends PaymentEvent>> TYPES = Map.of(
            "dev.difme.payments.PaymentInitiated.v1", PaymentEvent.PaymentInitiated.class,
            "dev.difme.payments.PaymentCompleted.v1", PaymentEvent.PaymentCompleted.class,
            "dev.difme.payments.PaymentCompensated.v1", PaymentEvent.PaymentCompensated.class,
            "dev.difme.payments.PaymentRejected.v1", PaymentEvent.PaymentRejected.class);

    private EventCodec() {}

    public static PaymentEvent decode(ObjectMapper json, String type, String payload) throws Exception {
        Class<? extends PaymentEvent> c = TYPES.get(type);
        if (c == null) throw new IllegalArgumentException("unknown event type " + type);
        return json.readValue(payload, c);
    }
}
