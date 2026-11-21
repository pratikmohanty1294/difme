package dev.difme.exchange.rules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecision;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecisionRuleResult;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecisionTableResult;
import org.finos.fluxnova.bpm.dmn.engine.DmnEngine;
import org.finos.fluxnova.bpm.dmn.engine.DmnEngineConfiguration;

/**
 * The compiled rules of one vertical pack (ADR-020, "embedded DMN"). Every {@code rules/*.dmn} file of the pack is
 * parsed and compiled once at load; {@link #evaluate} then runs entirely in-process. The engine is thread-safe once
 * built, so one instance serves all requests.
 *
 * <p>Decision tables in the packs use hit policy COLLECT with three outputs: {@code code}, {@code severity}
 * (BLOCK/WARN/INFO) and {@code message}. A request is allowed when no matched row has severity BLOCK.
 */
public final class PackRules {
    private static final DmnEngine ENGINE = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();

    private final String pack;
    private final Map<String, DmnDecision> decisions;
    /** Variables each decision's rule cells read (besides its input columns): a missing one is an error, not a no-match. */
    private final Map<String, java.util.Set<String>> cellVariables = new HashMap<>();

    private PackRules(String pack, Map<String, DmnDecision> decisions) {
        this.pack = pack;
        this.decisions = Collections.unmodifiableMap(decisions);
    }

    private static final java.util.regex.Pattern DECISION = java.util.regex.Pattern.compile("<decision\\s[^>]*id=\"([^\"]+)\"(.*?)</decision>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern ENTRY = java.util.regex.Pattern.compile("<inputEntry[^>]*>\\s*<text>(.*?)</text>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern STR = java.util.regex.Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|&quot;.*?&quot;");
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final java.util.Set<String> FEEL = java.util.Set.of("true", "false", "null", "not", "and", "or", "between", "in", "lt", "gt", "amp", "quot");

    /**
     * Records the variables the rule cells of each decision in {@code xml} compare against (e.g. {@code < minRiskScore}).
     * Without them a comparison with null silently matches nothing, so a forgotten input would switch a rule off.
     */
    private void scanCells(String xml) {
        java.util.regex.Matcher d = DECISION.matcher(xml);
        while (d.find()) {
            java.util.Set<String> names = new java.util.TreeSet<>();
            java.util.regex.Matcher e = ENTRY.matcher(d.group(2));
            while (e.find()) {
                java.util.regex.Matcher n = NAME.matcher(STR.matcher(e.group(1)).replaceAll(" "));
                while (n.find()) if (!FEEL.contains(n.group())) names.add(n.group());
            }
            cellVariables.merge(d.group(1), names, (a, b) -> { a.addAll(b); return a; });
        }
    }

    /** Loads and compiles every {@code *.dmn} under {@code packDir/rules}. */
    public static PackRules load(Path packDir) throws IOException {
        Map<String, DmnDecision> byId = new HashMap<>();
        List<String> xmls = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(packDir.resolve("rules"), "*.dmn")) {
            for (Path f : files) {
                byte[] xml = Files.readAllBytes(f);
                xmls.add(new String(xml, java.nio.charset.StandardCharsets.UTF_8));
                try (InputStream in = new java.io.ByteArrayInputStream(xml)) {
                    for (DmnDecision d : ENGINE.parseDecisions(in)) {
                        if (byId.put(d.getKey(), d) != null) {
                            throw new IllegalStateException("duplicate decision id " + d.getKey() + " in " + f);
                        }
                    }
                }
            }
        }
        if (byId.isEmpty()) throw new IllegalStateException("no decisions under " + packDir.resolve("rules"));
        PackRules r = new PackRules(packDir.getFileName().toString(), byId);
        xmls.forEach(r::scanCells);
        return r;
    }

    /** Opens a resource by its pack-relative name (e.g. {@code rules/x.dmn}); used to load packs from a classpath. */
    @FunctionalInterface
    public interface Opener {
        InputStream open(String relativeName) throws IOException;
    }

    /**
     * Loads the listed rule files through {@code opener}. This is how a service loads packs from its classpath, where a
     * directory cannot be listed; the file names come from the pack's {@code vertical.yaml} {@code rules:} list.
     */
    public static PackRules load(String pack, java.util.List<String> ruleFiles, Opener opener) throws IOException {
        Map<String, DmnDecision> byId = new HashMap<>();
        List<String> xmls = new ArrayList<>();
        for (String f : ruleFiles) {
            try (InputStream raw = opener.open(f)) {
                if (raw == null) throw new IOException("missing rule file " + f + " in pack " + pack);
                byte[] xml = raw.readAllBytes();
                xmls.add(new String(xml, java.nio.charset.StandardCharsets.UTF_8));
                InputStream in = new java.io.ByteArrayInputStream(xml);
                for (DmnDecision d : ENGINE.parseDecisions(in)) {
                    if (byId.put(d.getKey(), d) != null) throw new IllegalStateException("duplicate decision id " + d.getKey());
                }
            }
        }
        if (byId.isEmpty()) throw new IllegalStateException("no decisions in pack " + pack);
        PackRules r = new PackRules(pack, byId);
        xmls.forEach(r::scanCells);
        return r;
    }

    public String pack() {
        return pack;
    }

    public java.util.Set<String> decisionIds() {
        return decisions.keySet();
    }

    /** Evaluates one decision; returns every matched row as a {@link Finding} (empty when nothing matched). */
    public List<Finding> evaluate(String decisionId, Map<String, Object> inputs) {
        DmnDecision decision = decisions.get(decisionId);
        if (decision == null) throw new IllegalArgumentException("unknown decision " + decisionId + " in pack " + pack);
        for (String v : cellVariables.getOrDefault(decisionId, java.util.Set.of())) {
            if (!inputs.containsKey(v)) throw new IllegalArgumentException("decision " + decisionId + " needs the input " + v);
        }
        DmnDecisionTableResult result = ENGINE.evaluateDecisionTable(decision, inputs);
        List<Finding> findings = new ArrayList<>(result.size());
        for (DmnDecisionRuleResult row : result) {
            findings.add(new Finding(
                    (String) row.getEntry("code"),
                    Finding.Severity.valueOf((String) row.getEntry("severity")),
                    (String) row.getEntry("message")));
        }
        return findings;
    }

    /** True when no finding blocks the request. */
    public static boolean allowed(List<Finding> findings) {
        for (Finding f : findings) if (f.blocks()) return false;
        return true;
    }
}
