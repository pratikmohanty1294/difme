package dev.difme.core.cmd.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.difme.core.common.CommandContext;
import dev.difme.core.common.Money;
import dev.difme.core.common.Uuid7;
import dev.difme.core.common.events.PaymentEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentDeciderTest {
    private final Instant now = Instant.parse("2026-11-17T09:00:00Z");
    private final CommandContext ctx = new CommandContext(now, 42L, "00-abc-def-01");
    private final UUID a = Uuid7.derived(now, 1), b = Uuid7.derived(now, 2), key = Uuid7.derived(now, 3);

    private SubmitPayment cmd(UUID debit, UUID credit, Double score, boolean attested) {
        return new SubmitPayment(key, debit, credit, new Money(1000, "INR"), "MOBILE", score, attested, null);
    }

    @Test
    void acceptsValidPayment() {
        assertInstanceOf(PaymentEvent.PaymentInitiated.class, PaymentDecider.decide(cmd(a, b, 0.1, true), ctx));
    }

    @Test
    void sameInputsSameOutput() {   // deterministic replay (PDF §9)
        assertEquals(PaymentDecider.decide(cmd(a, b, 0.1, true), ctx), PaymentDecider.decide(cmd(a, b, 0.1, true), ctx));
    }

    @Test
    void rejectsSelfTransfer() {
        assertInstanceOf(PaymentEvent.PaymentRejected.class, PaymentDecider.decide(cmd(a, a, null, false), ctx));
    }

    @Test
    void attestedHighEdgeScoreIsSoftBlocked() {
        assertInstanceOf(PaymentEvent.PaymentRejected.class, PaymentDecider.decide(cmd(a, b, 0.9, true), ctx));
    }

    @Test
    void unattestedScoreIsIgnoredForServerSideCheck() {
        assertInstanceOf(PaymentEvent.PaymentInitiated.class, PaymentDecider.decide(cmd(a, b, 0.9, false), ctx));
    }
}
