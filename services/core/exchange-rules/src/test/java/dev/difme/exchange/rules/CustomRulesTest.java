package dev.difme.exchange.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.difme.exchange.rules.CustomRules.Action;
import dev.difme.exchange.rules.CustomRules.Field;
import dev.difme.exchange.rules.CustomRules.Rule;
import dev.difme.exchange.rules.CustomRules.Table;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomRulesTest {
    /** The PWA's default table (pwa/discountai/src/sim/dmn-compile.ts DEFAULT_CUSTOM). */
    static final Table DEFAULT = new Table(List.of(
            new Field("amount", "Card amount (₹)", "number", "amount"),
            new Field("merchant", "Merchant", "string", "merchant"),
            new Field("nightOrder", "Night order (00–06 IST)", "boolean", "hour < 6"),
            new Field("discountShare", "Discount left after my yield (%)", "number", "discountPct - yieldPct * (1 - discountPct / 100)")),
            List.of(new Rule(Map.of("nightOrder", "true"), Action.REJECT, "No purchases between midnight and 6 am."),
                    new Rule(Map.of("merchant", "not(\"Electronics Store A\",\"Electronics Store B\",\"Marketplace A\")"), Action.REJECT, "Merchant not on my list."),
                    new Rule(Map.of("amount", "> 9000"), Action.FLAG, "Large order: flag for my weekly review."),
                    new Rule(Map.of("discountShare", "< 10"), Action.REJECT, "Too little of the discount left for the buyer.")),
            Action.APPROVE);

    static Map<String, Object> order(double amount, String merchant, int hour) {
        Map<String, Object> o = new HashMap<>(Map.of("amount", amount, "listPrice", 10000, "discountPct", 20, "yieldPct", 7.5, "hour", hour));
        if (merchant != null) o.put("merchant", merchant);
        return o;
    }

    @Test
    void theDefaultTableDecidesFirstMatchTopToBottom() {
        CustomRules r = CustomRules.compile(DEFAULT, "mandate_custom_1");
        CustomRules.Outcome ok = r.evaluate(order(8000, "Electronics Store A", 14));
        assertEquals(Action.APPROVE, ok.action(), ok.message());
        CustomRules.Outcome night = r.evaluate(order(8000, "Electronics Store A", 3));
        assertEquals(Action.REJECT, night.action());
        assertEquals(1, night.rule());
        assertEquals(Action.REJECT, r.evaluate(order(8000, "Some Other Shop", 14)).action());
        CustomRules.Outcome big = r.evaluate(order(9500, "Electronics Store B", 14));
        assertEquals(Action.FLAG, big.action());
        assertEquals("Large order: flag for my weekly review.", big.message());
    }

    @Test
    void anUnknownMerchantIsNotOnTheList() {
        // The intent didn't say which merchant: null is "not one of these", so the partner's list rule rejects it.
        CustomRules r = CustomRules.compile(DEFAULT, "mandate_custom_2");
        assertEquals(Action.REJECT, r.evaluate(order(8000, null, 14)).action());
    }

    @Test
    void expressionsThatCouldReachJavaOrLoopAreRefused() {
        for (String bad : List.of(
                "\"x\".getClass().getName()",                                       // JUEL-style method call
                "${\"\".getClass()}",
                "(function(x) external {java: {class: \"java.lang.System\", method signature: \"exit(int)\"}})(1)",
                "count(for i in 1..100000000 return i)",
                "some x in [1,2] satisfies x > 1",
                "System.exit(1)")) {
            Table t = new Table(List.of(new Field("x", "x", "number", bad)), List.of(), Action.APPROVE);
            var e = assertThrows(IllegalArgumentException.class, () -> CustomRules.compile(t, "mandate_custom_bad"), bad);
            assertTrue(e.getMessage().contains("aren't allowed") || e.getMessage().contains("unknown name"), e.getMessage());
        }
    }

    @Test
    void structuralProblemsAreReportedInPlainWords() {
        Table t = new Table(List.of(new Field("Amount", "", "number", "amount"), new Field("if", "", "colour", "amount")),
                List.of(new Rule(Map.of("missing", "> 1"), Action.REJECT, "x")), Action.APPROVE);
        List<String> p = CustomRules.check(t);
        assertTrue(p.stream().anyMatch(s -> s.contains("must start with a lower-case letter")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("reserved word")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("type must be")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("is not one of your fields")), p.toString());
    }

    @Test
    void emptyEntriesAreProblemsNotCrashes() {
        var fields = new java.util.ArrayList<Field>(); fields.add(null);
        assertTrue(CustomRules.check(new Table(fields, List.of(), Action.APPROVE)).getFirst().contains("empty entries"));
        var tests = new HashMap<String, String>(); tests.put(null, "> 1");
        assertTrue(CustomRules.check(new Table(List.of(new Field("a", "", "number", "amount")),
                List.of(new Rule(tests, Action.REJECT, "x")), Action.APPROVE)).stream().anyMatch(x -> x.contains("has no field")));
    }

    @Test
    void aBadFeelTestIsACompileErrorOrFailsClosed() {
        Table t = new Table(List.of(new Field("amount", "", "number", "amount")),
                List.of(new Rule(Map.of("amount", "> > 5"), Action.APPROVE, "broken")), Action.APPROVE);
        try {
            CustomRules r = CustomRules.compile(t, "mandate_custom_broken");
            assertEquals(Action.REJECT, r.evaluate(order(8000, "Electronics Store A", 14)).action());   // fails closed
        } catch (IllegalArgumentException compileError) {
            assertTrue(compileError.getMessage().startsWith("The rules don't compile"), compileError.getMessage());
        }
    }
}
