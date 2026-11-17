package dev.difme.process.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldRulesTest {
    @Test
    void laterRowsOverrideEarlierOnes() {
        var rules = FieldRules.merge(List.of(
                Map.of("field", "dob", "required", true, "visible", true),
                Map.of("field", "dob", "required", false, "visible", false)));
        assertEquals(new FieldRule(false, false, null, null), rules.get("dob"));
    }

    @Test
    void validatesRequiredHiddenAndRange() {
        var rules = Map.of(
                "taxId", new FieldRule(true, true, null, null),
                "pepDetails", new FieldRule(false, false, null, null),
                "volume", new FieldRule(true, true, 0.0, 100.0));
        var v = FieldRules.validate(rules, Map.of("pepDetails", "x", "volume", 500));
        assertEquals(3, v.size(), v.toString());
        assertTrue(FieldRules.validate(rules, Map.of("taxId", "ABC", "volume", 50)).isEmpty());
    }
}
