package dev.difme.flink.bulk;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecision;
import org.finos.fluxnova.bpm.dmn.engine.DmnDecisionTableResult;
import org.finos.fluxnova.bpm.dmn.engine.DmnEngine;
import org.finos.fluxnova.bpm.dmn.engine.DmnEngineConfiguration;

/**
 * Embedded Fluxnova DMN engine (ADR-011): the pinned decision is parsed once per task and evaluated in-process for
 * every record — the same DMN tables that drive forms and processes (ADR-008).
 */
public final class DmnRules implements Serializable {
    private final String decisionKey;
    private final String dmnXml;
    private transient DmnEngine engine;
    private transient DmnDecision decision;

    public DmnRules(String decisionKey, String dmnXml) {
        this.decisionKey = decisionKey;
        this.dmnXml = dmnXml;
    }

    public void open() {
        engine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
        decision = engine.parseDecision(decisionKey, new ByteArrayInputStream(dmnXml.getBytes(StandardCharsets.UTF_8)));
    }

    public Object single(Map<String, Object> inputs) {
        if (engine == null) open();
        DmnDecisionTableResult r = engine.evaluateDecisionTable(decision, inputs);
        return r.isEmpty() ? null : r.getSingleResult().getSingleEntry();
    }
}
