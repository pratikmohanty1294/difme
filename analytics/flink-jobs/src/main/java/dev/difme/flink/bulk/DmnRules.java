package dev.difme.flink.bulk;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecision;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecisionTableResult;
import org.finos.fluxnova.bpm.dmn.engine.DmnEngine;
import org.finos.fluxnova.bpm.dmn.engine.DmnEngineConfiguration;

/**
 * Embedded Fluxnova DMN engine (ADR-011). A decision table is a pure function of its inputs, so results are memoised
 * by input tuple in a JVM-wide map shared by all task threads (ADR-012): with low-cardinality inputs (channel × risk)
 * 10M records cost a handful of real evaluations. The memo is bounded; past the bound it simply evaluates.
 */
public final class DmnRules implements Serializable {
    private static final int MEMO_LIMIT = 100_000;
    private static final Map<String, Optional<Object>> MEMO = new ConcurrentHashMap<>();

    private final String decisionKey;
    private final String dmnXml;
    private transient DmnEngine engine;
    private transient DmnDecision decision;

    public DmnRules(String decisionKey, String dmnXml) {
        this.decisionKey = decisionKey;
        this.dmnXml = dmnXml;
    }

    public synchronized void open() {
        if (engine != null) return;
        engine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
        decision = engine.parseDecision(decisionKey, new ByteArrayInputStream(dmnXml.getBytes(StandardCharsets.UTF_8)));
    }

    /** inputs are given as alternating name/value pairs to avoid building a Map per record on the hot path. */
    public Object single(String n1, Object v1, String n2, Object v2) {
        String memoKey = decisionKey + '\u0000' + dmnXml.hashCode() + '\u0000' + v1 + '\u0000' + v2;
        Optional<Object> hit = MEMO.get(memoKey);
        if (hit != null) return hit.orElse(null);
        if (engine == null) open();
        DmnDecisionTableResult r;
        synchronized (this) { r = engine.evaluateDecisionTable(decision, Map.of(n1, v1, n2, v2)); }
        Object value = r.isEmpty() ? null : r.getSingleResult().getSingleEntry();
        if (MEMO.size() < MEMO_LIMIT) MEMO.put(memoKey, Optional.ofNullable(value));
        return value;
    }
}
