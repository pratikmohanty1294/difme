package dev.difme.exchange.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Evaluates the real FR-01 (Buy It For Me / DiscountAI) pack from verticals/fr01-buy/rules. */
class PackRulesTest {
    static PackRules fr01;

    @BeforeAll
    static void load() throws Exception {
        fr01 = PackRules.load(Path.of("../../../verticals/fr01-buy"));
    }

    static Map<String, Object> intent(double risk, double maxPrice, double minViable, double listPrice, boolean sanctioned) {
        Map<String, Object> in = new HashMap<>();
        in.put("riskScore", risk);
        in.put("maxPrice", maxPrice);
        in.put("minViable", minViable);
        in.put("listPrice", listPrice);
        in.put("offerSanctioned", sanctioned);
        return in;
    }

    static List<String> codes(List<Finding> f) {
        return f.stream().map(Finding::code).sorted().toList();
    }

    @Test
    void loadsAllThreeDecisions() {
        assertEquals(java.util.Set.of("buyer_intent_eligibility", "investor_eligibility", "mandate_validation"), fr01.decisionIds());
        assertEquals("fr01-buy", fr01.pack());
    }

    @Test
    void cleanIntentIsAllowed() {
        List<Finding> f = fr01.evaluate("buyer_intent_eligibility", intent(900, 95_000, 90_000, 100_000, true));
        assertEquals(List.of("ELIGIBLE"), codes(f));
        assertTrue(PackRules.allowed(f), () -> "unexpected findings " + f);
    }

    @Test
    void lowRiskScoreBlocks() {
        List<Finding> f = fr01.evaluate("buyer_intent_eligibility", intent(700, 95_000, 90_000, 100_000, true));
        assertEquals(List.of("RISK_TOO_LOW"), codes(f));
        assertFalse(PackRules.allowed(f));
    }

    @Test
    void maxPriceBelowViableBlocks() {
        List<Finding> f = fr01.evaluate("buyer_intent_eligibility", intent(900, 85_000, 90_000, 100_000, true));
        assertEquals(List.of("MAX_PRICE_TOO_LOW"), codes(f));
    }

    @Test
    void aboveListPriceOnlyWarns() {
        List<Finding> f = fr01.evaluate("buyer_intent_eligibility", intent(900, 105_000, 90_000, 100_000, true));
        // COLLECT: the WARN row and the INFO "ELIGIBLE" row both match; nothing blocks.
        assertEquals(List.of("ABOVE_LIST_PRICE", "ELIGIBLE"), codes(f));
        assertEquals(Finding.Severity.WARN, f.stream().filter(x -> x.code().equals("ABOVE_LIST_PRICE")).findFirst().orElseThrow().severity());
        assertTrue(PackRules.allowed(f));
    }

    @Test
    void collectReturnsEveryMatchedRow() {
        List<Finding> f = fr01.evaluate("buyer_intent_eligibility", intent(700, 85_000, 90_000, 100_000, false));
        assertEquals(List.of("MAX_PRICE_TOO_LOW", "OFFER_NOT_SANCTIONED", "RISK_TOO_LOW"), codes(f));
    }

    @Test
    void unknownDecisionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> fr01.evaluate("nope", Map.of()));
    }
}
