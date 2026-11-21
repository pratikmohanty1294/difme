package dev.difme.exchange.rules;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecision;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecisionRuleResult;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecisionTableResult;
import org.finos.fluxnova.bpm.dmn.engine.DmnEngine;
import org.finos.fluxnova.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;

/**
 * A buying partner's own rules on top of a mandate's standard limits (FR-01 smart mandate factory). The partner sends
 * the structured table (fields with FEEL expressions over the order, rules of FEEL unary tests, an outcome each); the
 * exchange validates it, compiles it to a DMN 1.3 decision itself (no partner-written XML reaches the engine) and runs
 * it at every auction the mandate would bid in. Hit policy FIRST: the first matching rule decides, else the default.
 *
 * <p>Safety: every expression slot runs FEEL (forced in the engine and in the XML; JUEL could call Java methods), and
 * expressions may only name order attributes, the partner's own fields and a small set of FEEL built-ins. Constructs
 * that reach Java or loop ({@code function}/{@code external}, {@code for}/{@code some}/{@code every}) are rejected.
 */
public final class CustomRules {
    public enum Action { APPROVE, REJECT, FLAG }

    public record Field(String key, String label, String type, String expr) {}

    public record Rule(Map<String, String> tests, Action action, String message) {}

    public record Table(List<Field> fields, List<Rule> rules, Action defaultAction) {}

    /** What the rules decided for one order; {@code rule} is 1-based, 0 for the default. */
    public record Outcome(Action action, String message, int rule) {}

    /** Computed by the exchange for every bid; always available, can't be redefined by an uploaded catalog. */
    public static final Map<String, String> BUILT_IN = ordered(
            "amount", "number", "listPrice", "number", "discountPct", "number", "yieldPct", "number",
            "hour", "number", "weekday", "string", "buyerRiskScore", "number");
    /** Order attributes a vertical starts with until the operator uploads its own master list. */
    public static final Map<String, String> DEFAULT_ORDER_ATTRIBUTES = ordered(
            "merchant", "string", "category", "string", "brand", "string", "mcc", "string",
            "quantity", "number", "city", "string", "pincode", "string");
    /** Built-ins plus the default order attributes (callers without a catalog). */
    public static final Map<String, String> ORDER_FIELDS = merged(DEFAULT_ORDER_ATTRIBUTES);

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Built-ins plus a vertical's order attributes (name → number/string/boolean). */
    public static Map<String, String> merged(Map<String, String> orderAttributes) {
        Map<String, String> m = new LinkedHashMap<>(BUILT_IN);
        orderAttributes.forEach(m::putIfAbsent);
        return java.util.Collections.unmodifiableMap(m);
    }

    /** One attribute of a vertical's master list (uploaded by the exchange operator). */
    public record Attribute(String key, String type, String label, String example) {}

    /** Checks an uploaded master list. Empty means it can replace the current one. */
    public static List<String> checkAttributes(List<Attribute> list) {
        List<String> p = new ArrayList<>();
        if (list == null || list.isEmpty()) return List.of("The file has no attributes.");
        if (list.size() > 200) p.add("At most 200 attributes.");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Attribute a = list.get(i);
            String row = "Row " + (i + 1);
            if (a == null) { p.add(row + ": empty."); continue; }
            String k = a.key() == null ? "" : a.key().trim();
            if (!KEY.matcher(k).matches()) p.add(row + ": “" + k + "” must start with a lower-case letter and use only letters, digits or _ (max 31).");
            if (RESERVED.contains(k) || FEEL_WORDS.contains(k)) p.add(row + ": “" + k + "” is a reserved word.");
            if (BUILT_IN.containsKey(k)) p.add(row + ": “" + k + "” is computed by the exchange and can't be redefined.");
            if (!seen.add(k)) p.add(row + ": “" + k + "” appears twice.");
            if (a.type() == null || !List.of("number", "string", "boolean").contains(a.type().trim().toLowerCase(java.util.Locale.ROOT))) {
                p.add(row + ": type of “" + k + "” must be number, string or boolean.");
            }
            if (a.label() != null && a.label().length() > 80) p.add(row + ": label too long (max 80).");
        }
        return p;
    }

    private static final Pattern KEY = Pattern.compile("^[a-z][A-Za-z0-9_]{0,30}$");
    private static final Set<String> RESERVED = Set.of("and", "or", "not", "true", "false", "null", "for", "in", "if", "then",
            "else", "some", "every", "satisfies", "instance", "of", "function", "between", "return", "external");
    /** Words allowed in expressions besides attribute and field names: FEEL literals, operators and safe built-ins. */
    private static final Set<String> FEEL_WORDS = Set.of("true", "false", "null", "and", "or", "not", "between", "in", "if",
            "then", "else", "abs", "floor", "ceiling", "round", "min", "max", "sum", "mean", "count", "contains", "starts",
            "ends", "with", "upper", "lower", "case", "string", "length", "number", "substring", "before", "after", "modulo",
            "odd", "even", "matches", "decimal", "list", "append", "distinct", "values", "day", "week");
    private static final Pattern FORBIDDEN = Pattern.compile("\\b(function|external|for|some|every|satisfies|instance)\\b|\\$\\{|#\\{|java");
    private static final Pattern STRINGS = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final DmnEngine ENGINE = feelEngine();

    private static DmnEngine feelEngine() {
        DefaultDmnEngineConfiguration c = (DefaultDmnEngineConfiguration) DefaultDmnEngineConfiguration.createDefaultDmnEngineConfiguration();
        c.setDefaultInputExpressionExpressionLanguage("feel");
        c.setDefaultInputEntryExpressionLanguage("feel");
        c.setDefaultOutputEntryExpressionLanguage("feel");
        c.setDefaultLiteralExpressionLanguage("feel");
        return c.buildEngine();
    }

    private final Table table;
    private final DmnDecision decision;
    private final Set<String> names;

    private CustomRules(Table table, DmnDecision decision, Set<String> names) {
        this.table = table;
        this.decision = decision;
        this.names = names;
    }

    public Table table() {
        return table;
    }

    /** Validates and compiles; throws {@link IllegalArgumentException} with the problems in plain words. */
    public static CustomRules compile(Table t, String decisionId) {
        return compile(t, decisionId, ORDER_FIELDS);
    }

    /** Validates against the given attributes (built-ins plus the vertical's catalog) and compiles. */
    public static CustomRules compile(Table t, String decisionId, Map<String, String> attributes) {
        List<String> problems = check(t, attributes);
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join(" ", problems));
        try {
            List<DmnDecision> ds = ENGINE.parseDecisions(new ByteArrayInputStream(dmn(t, decisionId).getBytes(StandardCharsets.UTF_8)));
            return new CustomRules(t, ds.getFirst(), Set.copyOf(attributes.keySet()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The rules don't compile: " + rootMessage(e));
        }
    }

    /** Structural and safety checks. Empty means the table can be compiled. */
    public static List<String> check(Table t) {
        return check(t, ORDER_FIELDS);
    }

    public static List<String> check(Table t, Map<String, String> attributes) {
        List<String> p = new ArrayList<>();
        if (t == null || t.fields() == null || t.rules() == null) return List.of("Fields and rules are required.");
        if (t.fields().stream().anyMatch(java.util.Objects::isNull) || t.rules().stream().anyMatch(java.util.Objects::isNull)) return List.of("Fields and rules can't be empty entries.");
        if (t.fields().isEmpty()) p.add("Add at least one field.");
        if (t.fields().size() > 12) p.add("At most 12 fields.");
        if (t.rules().size() > 40) p.add("At most 40 rules.");
        if (t.defaultAction() == null) p.add("Choose a default outcome.");
        Set<String> seen = new HashSet<>();
        for (Field f : t.fields()) {
            String k = f.key() == null ? "" : f.key();
            if (!KEY.matcher(k).matches()) p.add("Field name “" + k + "” must start with a lower-case letter and use only letters, digits or _.");
            if (RESERVED.contains(k)) p.add("“" + k + "” is a reserved word.");
            if (!seen.add(k)) p.add("Field “" + k + "” is defined twice.");
            if (f.type() == null || !List.of("number", "string", "boolean").contains(f.type())) p.add("Field “" + k + "”: type must be number, string or boolean.");
            String e = f.expr() == null ? "" : f.expr();
            if (e.isBlank()) p.add("Field “" + k + "” needs an expression, e.g. an order attribute like amount.");
            if (e.length() > 200) p.add("Field “" + k + "”: expression too long.");
            p.addAll(unsafe("Field “" + k + "”", e, attributes.keySet()));
        }
        for (int i = 0; i < t.rules().size(); i++) {
            Rule r = t.rules().get(i);
            if (r.action() == null) p.add("Rule " + (i + 1) + ": choose an outcome.");
            if (r.message() != null && r.message().length() > 160) p.add("Rule " + (i + 1) + ": message too long.");
            if (r.tests() != null) {
                for (var e : r.tests().entrySet()) {
                    if (e.getKey() == null) { p.add("Rule " + (i + 1) + ": a test has no field."); continue; }
                    if (!seen.contains(e.getKey())) p.add("Rule " + (i + 1) + ": “" + e.getKey() + "” is not one of your fields.");
                    String v = e.getValue() == null ? "" : e.getValue();
                    if (v.length() > 200) p.add("Rule " + (i + 1) + ": a test is too long.");
                    p.addAll(unsafe("Rule " + (i + 1), v, Set.of()));
                }
            }
        }
        return p;
    }

    /** Attribute names a field expression reads (outside string literals): to find rules an upload would break. */
    public static Set<String> namesUsed(Table t) {
        Set<String> out = new HashSet<>();
        if (t == null || t.fields() == null) return out;
        for (Field f : t.fields()) {
            if (f == null || f.expr() == null) continue;
            Matcher m = IDENT.matcher(STRINGS.matcher(f.expr()).replaceAll("\"\""));
            while (m.find()) if (!FEEL_WORDS.contains(m.group())) out.add(m.group());
        }
        return out;
    }

    private static List<String> unsafe(String where, String expr, Set<String> names) {
        List<String> p = new ArrayList<>();
        String bare = STRINGS.matcher(expr).replaceAll("\"\"");
        if (FORBIDDEN.matcher(bare).find()) p.add(where + ": functions, loops and Java calls aren't allowed.");
        Matcher m = IDENT.matcher(bare);
        while (m.find()) {
            String w = m.group();
            if (!names.contains(w) && !FEEL_WORDS.contains(w)) { p.add(where + ": unknown name “" + w + "”."); break; }
        }
        return p;
    }

    /** Runs the rules for one order. An evaluation error fails closed: the mandate doesn't bid (REJECT). */
    public Outcome evaluate(Map<String, Object> order) {
        Map<String, Object> vars = new java.util.HashMap<>();
        for (String k : names) vars.put(k, order.get(k));
        try {
            DmnDecisionTableResult res = ENGINE.evaluateDecisionTable(decision, vars);
            DmnDecisionRuleResult r = res.getFirstResult();
            if (r == null) return new Outcome(table.defaultAction(), "No rule matched; default outcome.", 0);
            // (Object) casts: getEntry is generic, and String.valueOf would otherwise pick the char[] overload.
            String action = String.valueOf((Object) r.getEntry("action"));
            String ruleId = String.valueOf((Object) r.getEntry("rule"));
            return new Outcome(Action.valueOf(action), String.valueOf((Object) r.getEntry("message")), Integer.parseInt(ruleId));
        } catch (RuntimeException e) {
            return new Outcome(Action.REJECT, "Your custom rules could not be evaluated for this order (" + rootMessage(e) + ").", -1);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
        return m.length() > 200 ? m.substring(0, 200) + "…" : m;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String feelString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** DMN 1.3 XML, same shape as the PWA's compileDmn, plus a rule-number output; FEEL named on every expression. */
    static String dmn(Table t, String decisionId) {
        String feel = "https://www.omg.org/spec/DMN/20191111/FEEL/";
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\""
                + " id=\"def_" + decisionId + "\" name=\"Buying partner custom rules\" namespace=\"https://difme.example/dmn/custom\""
                + " expressionLanguage=\"" + feel + "\">\n  <decision id=\"" + decisionId + "\" name=\"Buying partner custom rules\">\n"
                + "    <decisionTable id=\"dt_" + decisionId + "\" hitPolicy=\"FIRST\">\n");
        for (int i = 0; i < t.fields().size(); i++) {
            Field f = t.fields().get(i);
            x.append("      <input id=\"in_").append(i).append("\" label=\"").append(esc(f.label() == null || f.label().isBlank() ? f.key() : f.label()))
                    .append("\">")
                    .append("<inputExpression id=\"ie_").append(i).append("\" typeRef=\"").append(f.type()).append("\" expressionLanguage=\"").append(feel)
                    .append("\"><text>").append(esc(f.expr())).append("</text></inputExpression></input>\n");
        }
        x.append("      <output id=\"out_action\" name=\"action\" typeRef=\"string\" />\n")
         .append("      <output id=\"out_message\" name=\"message\" typeRef=\"string\" />\n")
         .append("      <output id=\"out_rule\" name=\"rule\" typeRef=\"string\" />\n");
        for (int i = 0; i < t.rules().size(); i++) {
            Rule r = t.rules().get(i);
            row(x, "r" + (i + 1), t, r.tests(), r.action(), r.message() == null || r.message().isBlank() ? "Rule " + (i + 1) : r.message(), i + 1, feel);
        }
        row(x, "r_default", t, Map.of(), t.defaultAction(), "No rule matched; default outcome.", 0, feel);
        return x.append("    </decisionTable>\n  </decision>\n</definitions>\n").toString();
    }

    private static void row(StringBuilder x, String id, Table t, Map<String, String> tests, Action action, String message, int n, String feel) {
        x.append("      <rule id=\"").append(id).append("\">");
        for (int j = 0; j < t.fields().size(); j++) {
            String v = tests == null ? null : tests.get(t.fields().get(j).key());
            v = v == null || v.isBlank() ? "-" : v.trim();
            x.append("<inputEntry id=\"").append(id).append("_").append(j).append("\" expressionLanguage=\"").append(feel).append("\"><text>")
             .append(esc(v)).append("</text></inputEntry>");
        }
        for (String[] o : new String[][] {{"o0", action.name()}, {"o1", message}, {"o2", String.valueOf(n)}}) {
            x.append("<outputEntry id=\"").append(id).append("_").append(o[0]).append("\" expressionLanguage=\"").append(feel).append("\"><text>")
             .append(esc(feelString(o[1]))).append("</text></outputEntry>");
        }
        x.append("</rule>\n");
    }
}
