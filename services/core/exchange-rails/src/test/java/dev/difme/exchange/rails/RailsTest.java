package dev.difme.exchange.rails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.difme.exchange.rails.AgentCredentialPort.Instruction;
import dev.difme.exchange.rails.Failover.Candidate;
import dev.difme.exchange.rails.Failover.SlashingBps;
import dev.difme.exchange.rails.MerchantCheckoutPort.Authorization;
import dev.difme.exchange.rails.sim.SimRails;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RailsTest {
    static final long NOW = 1_800_000_000_000L;
    static final SlashingBps FR01 = new SlashingBps(200, 50);     // verticals/fr01-buy: iso_51_bps, other_decline_bps
    static final String MERCHANT = "m-amz-01";

    // ---- ISO 8583 classification drives slashing ----
    @Test
    void responseCodesMapToOutcomes() {
        assertEquals(Iso8583.Outcome.APPROVED, Iso8583.classify("00"));
        assertEquals(Iso8583.Outcome.DECLINE_FUNDS, Iso8583.classify("51"));
        assertEquals(Iso8583.Outcome.DECLINE_FUNDS, Iso8583.classify("61"));
        assertEquals(Iso8583.Outcome.RETRYABLE, Iso8583.classify("91"));
        assertEquals(Iso8583.Outcome.DECLINE_OTHER, Iso8583.classify("05"));
    }

    // ---- Agentic token: single use, merchant and amount bound, expires ----
    @Test
    void agentTokenIsSingleUseAndCapped() {
        SimRails rails = new SimRails();
        var t = rails.credentials.issue(new Instruction(UUID.randomUUID(), 7, MERCHANT, 8_000_00, "INR"), NOW);
        assertEquals(t, rails.credentials.issue(t.instruction(), NOW), "idempotent per instruction");
        assertFalse(rails.credentials.redeem(t.token(), "other-merchant", 8_000_00, NOW));
        assertFalse(rails.credentials.redeem(t.token(), MERCHANT, 8_000_01, NOW));
        assertTrue(rails.credentials.redeem(t.token(), MERCHANT, 8_000_00, NOW));
        assertFalse(rails.credentials.redeem(t.token(), MERCHANT, 8_000_00, NOW), "spent");
        var late = rails.credentials.issue(new Instruction(UUID.randomUUID(), 8, MERCHANT, 100, "INR"), NOW);
        assertFalse(rails.credentials.redeem(late.token(), MERCHANT, 100, NOW + 16 * 60_000L), "expired");
    }

    // ---- 0100/0110: issuer limit, duplicate STAN replay, reversal restores the limit ----
    @Test
    void checkoutAuthorisesAgainstTheIssuerLimitAndReplaysDuplicates() {
        SimRails rails = new SimRails();
        rails.checkout.limit(7, 10_000_00);
        var t = rails.credentials.issue(new Instruction(UUID.randomUUID(), 7, MERCHANT, 8_000_00, "INR"), NOW);
        var ok = rails.checkout.authorize(new Authorization("000001", t.token(), MERCHANT, 8_000_00, "INR"), NOW);
        assertTrue(ok.approved());
        assertEquals(ok, rails.checkout.authorize(new Authorization("000001", t.token(), MERCHANT, 8_000_00, "INR"), NOW));
        var t2 = rails.credentials.issue(new Instruction(UUID.randomUUID(), 7, MERCHANT, 3_000_00, "INR"), NOW);
        assertEquals("51", rails.checkout.authorize(new Authorization("000002", t2.token(), MERCHANT, 3_000_00, "INR"), NOW).responseCode());
        rails.checkout.reverse(ok.rrn());
        assertTrue(rails.checkout.authorize(new Authorization("000003", t2.token(), MERCHANT, 3_000_00, "INR"), NOW).approved());
        assertEquals("05", rails.checkout.authorize(new Authorization("000004", "tok_unknown", MERCHANT, 1, "INR"), NOW).responseCode());
    }

    // ---- Failover: 51 slashes 2 % of A × μ 1.2; 91 moves on free; backup completes ----
    @Test
    void failoverSlashesOverCommitmentAndCompletesOnABackup() {
        long a = 8_000_00;
        AtomicInteger calls = new AtomicInteger();
        var result = Failover.run(List.of(new Candidate(1, 3_000_00), new Candidate(2, 3_000_00), new Candidate(3, 3_000_00)),
                a, FR01, partner -> { calls.incrementAndGet(); return partner == 1 ? "51" : partner == 2 ? "91" : "00"; });
        assertTrue(result.fulfilled());
        assertEquals(3, result.winner());
        assertEquals(3, calls.get());
        assertEquals(Math.round(a * 0.02 * 1.2), result.attempts().get(0).slashMinor(), "β 200 bps, μ seamless");
        assertEquals(0, result.attempts().get(1).slashMinor(), "issuer unavailable is not the partner's fault");
        assertEquals(0, result.attempts().get(2).slashMinor());
    }

    @Test
    void lastCandidateFailingUsesReexecutionFrictionAndSlashIsCappedByCollateral() {
        var r = Failover.run(List.of(new Candidate(9, 100)), 8_000_00, FR01, p -> "05");
        assertFalse(r.fulfilled());
        assertEquals(100, r.totalSlashMinor(), "Λ never exceeds the locked collateral");
        var r2 = Failover.run(List.of(new Candidate(9, 1_000_000_00)), 8_000_00, FR01, p -> "05");
        assertEquals(Math.round(8_000_00 * 0.005 * 1.5), r2.totalSlashMinor(), "β 50 bps, μ re-execute");
    }

    // ---- Escrow conservation and idempotency ----
    @Test
    void escrowNeverReleasesMoreThanItHolds() {
        SimRails rails = new SimRails();
        rails.escrow.hold("h1", "buyer", 9_000_00);
        assertEquals(rails.escrow.get("h1"), rails.escrow.hold("h1", "buyer", 9_000_00));
        assertThrows(IllegalStateException.class, () -> rails.escrow.hold("h1", "buyer", 1));
        rails.escrow.release("h1", "r1", "partner", 8_000_00);
        rails.escrow.release("h1", "r1", "partner", 8_000_00);             // retry: applied once
        assertEquals(1_000_00, rails.escrow.get("h1").remainingMinor());
        assertThrows(IllegalStateException.class, () -> rails.escrow.release("h1", "r2", "partner", 1_000_01));
        var refunded = rails.escrow.refund("h1");
        assertEquals(EscrowPort.State.REFUNDED, refunded.state());
        assertEquals(9_000_00, refunded.releasedMinor() + refunded.refundedMinor());
    }

    // ---- UPI Reserve Pay: partial debits, no overdraw, nothing after revoke ----
    @Test
    void upiReserveDebitsWithinTheBlock() {
        SimRails rails = new SimRails();
        rails.upi.reserve("b1", "buyer@upi", 5_000_00);
        assertTrue(rails.upi.debit("b1", "d1", 3_000_00));
        assertTrue(rails.upi.debit("b1", "d1", 3_000_00), "idempotent");
        assertFalse(rails.upi.debit("b1", "d2", 2_000_01));
        assertTrue(rails.upi.debit("b1", "d3", 2_000_00));
        rails.upi.reserve("b2", "buyer@upi", 100);
        rails.upi.revoke("b2");
        assertFalse(rails.upi.debit("b2", "d1", 1));
    }

    @Test
    void stablecoinReceiptIsDeterministicPerRequest() {
        SimRails rails = new SimRails();
        var t = new StablecoinPort.Transfer("req-1", "exchange", "partner", 8_000_00, "USDC");
        assertEquals(rails.stablecoin.transfer(t), rails.stablecoin.transfer(t));
        assertNotEquals(rails.stablecoin.transfer(t).txRef(),
                rails.stablecoin.transfer(new StablecoinPort.Transfer("req-2", "exchange", "partner", 8_000_00, "USDC")).txRef());
    }

    // ---- A pack goes live only when every rail it names is implemented ----
    @Test
    void registryRefusesAPackWithMissingRails() {
        SimRails rails = new SimRails();
        rails.registry.requireAll("fr01-buy", List.of("card-agent-credential", "merchant-psp-checkout", "agent-pa-escrow",
                "upi-reserve-pay", "stablecoin"));
        var e = assertThrows(IllegalStateException.class,
                () -> new RailRegistry().requireAll("fr02-book", List.of("gds-booking", "agent-pa-escrow")));
        assertTrue(e.getMessage().contains("gds-booking") && e.getMessage().contains("agent-pa-escrow"));
    }

    // ---- FR-01 end to end on the simulators: hold B, winner declines 51, backup buys, split release ----
    @Test
    void fr01PurchaseFailsOverAndSettlesWithConservation() {
        SimRails rails = new SimRails();
        long p = 10_000_00, dp = 2_000_00, a = p - dp, y = 300_00, f = 100_00, b = a + y + f;   // A = P − d·P; B = A + Y + F
        UUID mandate = UUID.randomUUID();
        rails.escrow.hold("buyer-" + mandate, "buyer", b);
        rails.checkout.limit(11, 5_000_00).limit(12, 50_000_00);          // winner over-committed; backup has headroom
        AtomicInteger stan = new AtomicInteger();
        var result = Failover.run(List.of(new Candidate(11, 2_000_00), new Candidate(12, 2_000_00)), a, FR01, partner -> {
            var token = rails.credentials.issue(new Instruction(mandate, partner, MERCHANT, a, "INR"), NOW);
            return rails.checkout.authorize(new Authorization(String.format("%06d", stan.incrementAndGet()), token.token(),
                    MERCHANT, a, "INR"), NOW).responseCode();
        });
        assertEquals(12, result.winner());
        assertEquals(Math.round(a * 0.02 * 1.2), result.totalSlashMinor());
        rails.escrow.release("buyer-" + mandate, "payout", "partner-12", a + y);   // payout = A + Y
        var h = rails.escrow.release("buyer-" + mandate, "fee", "exchange", f);
        assertEquals(EscrowPort.State.SETTLED, h.state());
        assertEquals(b, h.releasedMinor(), "B = A + Y + F fully accounted for");
        assertEquals(p - b, dp - y - f, "buyer saving S = P − B");
    }
}
