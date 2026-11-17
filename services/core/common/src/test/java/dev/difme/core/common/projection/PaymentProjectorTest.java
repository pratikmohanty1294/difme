package dev.difme.core.common.projection;

import static org.junit.jupiter.api.Assertions.*;

import dev.difme.core.common.Money;
import dev.difme.core.common.Uuid7;
import dev.difme.core.common.events.PaymentEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentProjectorTest {
    private final Instant t0 = Instant.parse("2026-05-01T00:00:00Z");
    private final UUID pay = Uuid7.derived(t0, 1);

    private List<PaymentEvent> history() {
        return List.of(
                new PaymentEvent.PaymentInitiated(Uuid7.derived(t0, 2), pay, t0, Uuid7.derived(t0, 3),
                        Uuid7.derived(t0, 4), Uuid7.derived(t0, 5), new Money(1500, "INR"), "MOBILE", 0.1, null),
                new PaymentEvent.PaymentCompleted(Uuid7.derived(t0, 6), pay, t0.plusSeconds(2), pay.toString(), "tb-1"));
    }

    @Test
    void replayIsDeterministic() {
        PaymentView a = null, b = null;
        for (PaymentEvent e : history()) a = PaymentProjector.apply(a, e);
        for (PaymentEvent e : history()) b = PaymentProjector.apply(b, e);
        assertEquals(a.fingerprint(), b.fingerprint());
        assertEquals("COMPLETED", a.status);
        assertEquals(2, a.version);
        assertEquals("2026-05-01T00:00:02Z", a.updatedAt);
    }

    @Test
    void doesNotMutateInput() {
        PaymentView first = PaymentProjector.apply(null, history().get(0));
        String before = first.fingerprint();
        PaymentProjector.apply(first, history().get(1));
        assertEquals(before, first.fingerprint());
    }
}
