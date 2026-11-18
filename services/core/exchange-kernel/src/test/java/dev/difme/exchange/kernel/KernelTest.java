package dev.difme.exchange.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.difme.exchange.kernel.EvidenceChain.Signal;
import dev.difme.exchange.kernel.EvidenceChain.Type;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KernelTest {

    // ---- FR-17 universal score: the v4.1 SD-C1 worked example (A ₹8,000, d·P ₹2,000, Lmax 50 ms, weights .5/.4/.1) ----
    @Test
    void scoringReproducesTheWorkedExample() {
        Weights w = new Weights(0.50, 0.40, 0.10);
        BidBook book = new BidBook(8);
        int a = book.add(1, 640_00, 60, 10_000);
        int b = book.add(2, 600_00, 99, 5_000);
        int c = book.add(3, 560_00, 90, 45_000);
        assertEquals(3, book.rank(2_000_00, w, 50_000));
        assertEquals(0.560, book.score(a), 1e-9);
        assertEquals(0.736, book.score(b), 1e-9);
        assertEquals(0.630, book.score(c), 1e-9);
        assertEquals(2, book.node(book.atRank(0)), "B wins");
        assertEquals(3, book.node(book.atRank(1)), "C is the first hot backup");
        assertEquals(1, book.node(book.atRank(2)));
    }

    @Test
    void ranksTiesByLowerAskThenArrivalAndRejectsAsksAboveTheCeiling() {
        Weights w = new Weights(0.0, 1.0, 0.0);              // score depends on reputation only → forced ties
        BidBook book = new BidBook(4);
        book.add(10, 500, 90, 0);
        book.add(11, 400, 90, 0);
        book.add(12, 400, 90, 0);
        book.add(13, 2_000, 99, 0);                           // above maxAcceptable → rejected
        assertEquals(3, book.rank(1_000, w, 1));
        assertEquals(11, book.node(book.atRank(0)));
        assertEquals(12, book.node(book.atRank(1)));
        assertEquals(10, book.node(book.atRank(2)));
        book.reset();
        assertEquals(0, book.size());
        assertEquals(-1, fill(new BidBook(1)), "full window refuses the bid");
    }

    private static int fill(BidBook b) {
        b.add(1, 1, 50, 0);
        return b.add(2, 1, 50, 0);
    }

    @Test
    void weightsMustSumToOne() {
        assertThrows(IllegalArgumentException.class, () -> new Weights(0.6, 0.3, 0.2));
        new Weights(0.60, 0.25, 0.15);
    }

    // ---- FR-14 collateral, FR-15 slashing ----
    @Test
    void collateralAndSlash() {
        assertEquals(40_000, Risk.requiredCollateral(100_000, 0.20, 1.0, Risk.Tier.COLD_START, 1_000));
        assertEquals(12_000, Risk.requiredCollateral(100_000, 0.20, 1.0, Risk.Tier.ENTERPRISE, 1_000));
        assertEquals(5_000, Risk.requiredCollateral(1_000, 0.05, 1.0, Risk.Tier.ESTABLISHED, 5_000), "floor D_min");
        // V 1,00,000 · (0.02 + 0.01 · 3 min) · 1.2 = 6,000, capped by the locked collateral.
        assertEquals(6_000, Risk.slash(40_000, 100_000, Risk.Fault.MISREPRESENTATION.beta, 0.01, 3, Risk.Failover.SEAMLESS));
        assertEquals(4_000, Risk.slash(4_000, 100_000, Risk.Fault.MISREPRESENTATION.beta, 0.01, 3, Risk.Failover.SEAMLESS));
        assertEquals(500, Risk.slash(40_000, 100_000, Risk.Fault.TRANSIENT.beta, 0.01, -5, Risk.Failover.NONE), "no negative overrun");
    }

    // ---- FR-16 reputation ----
    @Test
    void reputationGainsSlowlyAndDropsFast() {
        double up = Reputation.update(80, 100, 1_000, 1_000, 0.1);      // α = 0.1 · ln 2
        double down = Reputation.update(80, 0, 1_000, 1_000, 0.1);      // α = 0.1 · ln 2 · 2.5
        assertEquals(80 + 0.1 * Math.log(2) * 20, up, 1e-9);
        assertEquals(80 - 0.25 * Math.log(2) * 80, down, 1e-9);
        assertTrue(80 - down > (up - 80) * 2.4, "asymmetric");
        assertTrue(Reputation.update(80, 100, 1, 1_000_000, 0.1) - 80 < 0.001, "micro-task farming barely moves the score");
        assertEquals(0, Reputation.update(80, 0, Long.MAX_VALUE / 2, 1, 0.5), 1e-9, "α_eff is capped at 1");
        assertEquals(85, Reputation.global(new double[] {80, 90}, new double[] {1, 1}), 1e-9);
    }

    // ---- conservation identity: the reference scenario ($2,000, 20 %, Y $35, F $15 → B $1,650, saves $350) ----
    @Test
    void conservationIdentity() {
        CommerceSplit s = CommerceSplit.of(2_000_00, 2_000, 35_00, 15_00, 1_000);
        assertEquals(1_600_00, s.a());
        assertEquals(1_650_00, s.b());
        assertEquals(350_00, s.buyerSaves());
        assertEquals(1_635_00, s.payout());
        assertEquals(1_50, s.guaranteeFund());
        assertThrows(IllegalArgumentException.class, () -> CommerceSplit.of(1_000, 100, 10, 5, 0), "Y + F ≥ discount → no saving");
    }

    // ---- canonical JSON + evidence chain ----
    @Test
    void canonicalJsonMatchesTheBrowserImplementation() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "cart");
        m.put("seq", 0);
        m.put("chain", "chn_B_req-1");
        m.put("prev_hash", null);
        m.put("signal", "Deterministic");
        m.put("payload", Map.of("total", 165_000L, "currency", "INR", "all_in_price", true));
        assertEquals("f7288b388d5c329a3038c4a35ffb4a5ac18e0a476a41303442c4431b50105dd0", Canonical.sha256Hex(Canonical.json(m)),
                "same digest as JSON.stringify with sorted keys (src/sim/crypto.ts)");
        assertEquals("[\"a\\\"b\\n\\u0001\",[1,2]]", Canonical.json(List.of("a\"b\n\u0001", List.of(1, 2))));
        assertThrows(IllegalArgumentException.class, () -> Canonical.json(Map.of("x", 1.5)));
    }

    @Test
    void evidenceChainSealsVerifiesAndDetectsTampering() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        KeyPair op = g.generateKeyPair(), pg = g.generateKeyPair();
        Map<String, java.security.PublicKey> registry = Map.of("evidence-service", op.getPublic(), "agent-pa-pg", pg.getPublic());

        EvidenceChain c = new EvidenceChain("chn_B_req-1", EvidenceChain.Kind.B);
        c.append(Type.intent, Signal.Deterministic, Map.of("order_id", "REQ-1", "max_price", 170_000L));
        c.append(Type.cart, Signal.Deterministic, Map.of("total", 165_000L, "currency", "INR"));
        c.append(Type.authorization, Signal.Attested, Map.of("amount", 165_000L, "currency", "INR", "hold_id", "H-1"));
        c.seal("evidence-service", op.getPrivate(), Map.of("agent-pa-pg", pg.getPrivate()));
        var shipped = c.append(Type.fulfillment, Signal.Attested, Map.of("tracking", "TRK-1"));
        assertTrue(shipped.postSeal());

        var v = c.verify(registry::get);
        assertTrue(v.ok(), v.notes().toString());
        assertTrue(v.must1());

        EvidenceChain bad = new EvidenceChain("chn_B_req-2", EvidenceChain.Kind.B);
        bad.append(Type.cart, Signal.Deterministic, Map.of("total", 165_000L, "currency", "INR"));
        bad.append(Type.authorization, Signal.Attested, Map.of("amount", 160_000L, "currency", "INR"));
        bad.seal("evidence-service", op.getPrivate(), Map.of("agent-pa-pg", pg.getPrivate()));
        var vb = bad.verify(registry::get);
        assertFalse(vb.ok());
        assertFalse(vb.must1(), "authorisation ≠ cart total");

        var unknownSigner = c.verify(name -> null);
        assertFalse(unknownSigner.seal());

        EvidenceChain empty = new EvidenceChain("chn_S_x", EvidenceChain.Kind.S);
        empty.append(Type.intent, Signal.Deterministic, Map.of());
        assertNull(empty.verify(registry::get).must1());
    }
}
