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
import org.finos.fluxnova.bpm.dmn.engine.DmnDecisionResultEntries;
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

    private PackRules(String pack, Map<String, DmnDecision> decisions) {
        this.pack = pack;
        this.decisions = Collections.unmodifiableMap(decisions);
    }

    /** Loads and compiles every {@code *.dmn} under {@code packDir/rules}. */
    public static PackRules load(Path packDir) throws IOException {
        Map<String, DmnDecision> byId = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(packDir.resolve("rules"), "*.dmn")) {
            for (Path f : files) {
                try (InputStream in = Files.newInputStream(f)) {
                    for (DmnDecision d : ENGINE.parseDecisions(in)) {
                        if (byId.put(d.getKey(), d) != null) {
                            throw new IllegalStateException("duplicate decision id " + d.getKey() + " in " + f);
                        }
                    }
                }
            }
        }
        if (byId.isEmpty()) throw new IllegalStateException("no decisions under " + packDir.resolve("rules"));
        return new PackRules(packDir.getFileName().toString(), byId);
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
        DmnDecisionTableResult result = ENGINE.evaluateDecisionTable(decision, inputs);
        List<Finding> findings = new ArrayList<>(result.size());
        for (DmnDecisionResultEntries row : result) {
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
