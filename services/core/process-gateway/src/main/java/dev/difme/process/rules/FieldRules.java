package dev.difme.process.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure functions over DMN results: merge COLLECT rows into per-field rules (later rows override earlier ones) and
 * validate a submission against them. The same code runs for live UX hints and the authoritative submit check.
 */
public final class FieldRules {
    public record Violation(String field, String reason) {}

    private FieldRules() {}

    public static Map<String, FieldRule> merge(List<Map<String, Object>> rows) {
        Map<String, FieldRule> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object field = row.get("field");
            if (field == null) continue;
            FieldRule r = new FieldRule(bool(row.get("required")), bool(row.get("visible")), num(row.get("min")), num(row.get("max")));
            out.merge(field.toString(), r, FieldRule::overlay);
        }
        return out;
    }

    public static List<Violation> validate(Map<String, FieldRule> rules, Map<String, Object> data) {
        List<Violation> v = new ArrayList<>();
        rules.forEach((field, r) -> {
            Object value = data.get(field);
            boolean present = value != null && !(value instanceof String s && s.isBlank());
            boolean visible = r.visible() == null || r.visible();
            if (!visible && present) v.add(new Violation(field, "must be empty when hidden"));
            if (visible && Boolean.TRUE.equals(r.required()) && !present) v.add(new Violation(field, "is required"));
            if (present && (r.min() != null || r.max() != null)) {
                Double n = num(value);
                if (n == null) v.add(new Violation(field, "must be a number"));
                else {
                    if (r.min() != null && n < r.min()) v.add(new Violation(field, "must be >= " + r.min()));
                    if (r.max() != null && n > r.max()) v.add(new Violation(field, "must be <= " + r.max()));
                }
            }
        });
        return v;
    }

    private static Boolean bool(Object o) {
        return o == null ? null : o instanceof Boolean b ? b : Boolean.valueOf(o.toString());
    }

    private static Double num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.valueOf(o.toString()); } catch (NumberFormatException e) { return null; }
    }
}
