package dev.difme.process;

import static org.junit.jupiter.api.Assertions.*;

import dev.difme.process.rules.FieldRule;
import dev.difme.process.rules.FieldRules;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.ProcessEngineConfiguration;
import org.finos.fluxnova.bpm.engine.externaltask.LockedExternalTask;
import org.finos.fluxnova.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.finos.fluxnova.bpm.engine.repository.DeploymentBuilder;
import org.finos.fluxnova.bpm.engine.runtime.ProcessInstance;
import org.finos.fluxnova.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Deploys every model in processes/ to a real in-memory Fluxnova 3.1.1 engine and executes it. */
class ModelsOnFluxnovaEngineTest {
    static ProcessEngine engine;

    @BeforeAll
    static void deployAll() throws Exception {
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl)
                ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:difme-models;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);
        cfg.setJobExecutorActivate(false);
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setHistoryTimeToLive("P30D");
        engine = cfg.buildProcessEngine();

        Path root = Path.of(System.getProperty("user.dir")).resolve("../../../processes").normalize();
        DeploymentBuilder d = engine.getRepositoryService().createDeployment().name("difme-processes");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().matches(".*\\.(bpmn|dmn|cmmn)$")).toList()) {
                InputStream in = Files.newInputStream(p);
                d.addInputStream(p.getFileName().toString(), in);
            }
        }
        d.deploy();
    }

    @AfterAll
    static void close() {
        engine.close();
    }

    private static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void kycRiskDecision() {
        var ds = engine.getDecisionService();
        assertEquals("LOW", ds.evaluateDecisionTableByKey("kyc-risk", vars("pep", false, "countryRisk", "LOW", "expectedMonthlyVolumeMinor", 1_000L)).getSingleResult().getSingleEntry());
        assertEquals("HIGH", ds.evaluateDecisionTableByKey("kyc-risk", vars("pep", true, "countryRisk", "LOW", "expectedMonthlyVolumeMinor", 1_000L)).getSingleResult().getSingleEntry());
        assertEquals("MEDIUM", ds.evaluateDecisionTableByKey("kyc-risk", vars("pep", false, "countryRisk", "LOW", "expectedMonthlyVolumeMinor", 20_000_000L)).getSingleResult().getSingleEntry());
    }

    @Test
    void formRulesForIndianBusiness() {
        List<Map<String, Object>> rows = engine.getDecisionService()
                .evaluateDecisionTableByKey("kyc-form-rules", vars("accountType", "BUSINESS", "country", "IN", "pep", false))
                .getResultList();
        Map<String, FieldRule> rules = FieldRules.merge(rows);
        assertEquals(Boolean.TRUE, rules.get("companyRegistrationNumber").required());
        assertEquals(Boolean.FALSE, rules.get("dateOfBirth").visible());
        assertEquals(Boolean.TRUE, rules.get("taxId").required());
        assertEquals(Boolean.FALSE, rules.get("pepDetails").visible());
        assertEquals(1_000_000_000d, rules.get("expectedMonthlyVolume").max());
        assertFalse(FieldRules.validate(rules, Map.of("companyRegistrationNumber", "U1234", "taxId", "ABCDE1234F",
                "expectedMonthlyVolume", 10)).iterator().hasNext());
    }

    @Test
    void paymentLimitsConfig() {
        Object limit = engine.getDecisionService()
                .evaluateDecisionTableByKey("payment-limits", vars("channel", "MOBILE", "riskLevel", "MEDIUM"))
                .getSingleResult().getSingleEntry();
        assertEquals(5_000_000L, ((Number) limit).longValue());
    }

    @Test
    void lowRiskGoesStraightToTemporalThenWaitsForCallback() {
        ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("kyc-onboarding", "bk-low",
                vars("pep", false, "countryRisk", "LOW", "expectedMonthlyVolumeMinor", 1_000L));
        List<LockedExternalTask> tasks = engine.getExternalTaskService().fetchAndLock(5, "test")
                .topic("temporal:AccountProvisioningWorkflow", 10_000).execute();
        assertEquals(1, tasks.size());
        engine.getExternalTaskService().complete(tasks.get(0).getId(), "test");
        assertEquals(1, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi.getId()).count(),
                "waiting for the Temporal callback");
        engine.getRuntimeService().createMessageCorrelation("account-provisioned").processInstanceId(pi.getId())
                .setVariable("accountId", "acc-1").correlate();
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi.getId()).count());
    }

    @Test
    void highRiskNeedsComplianceReview() {
        ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("kyc-onboarding", "bk-high",
                vars("pep", true, "countryRisk", "LOW", "expectedMonthlyVolumeMinor", 1_000L));
        Task review = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertEquals("compliance-review", review.getTaskDefinitionKey());
        engine.getTaskService().complete(review.getId(), vars("approved", false, "reviewNotes", "insufficient evidence"));
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi.getId()).count());
    }

    @Test
    void disputeCaseStarts() {
        var ci = engine.getCaseService().createCaseInstanceByKey("dispute-case");
        assertTrue(engine.getCaseService().createCaseExecutionQuery().caseInstanceId(ci.getId()).activityId("pi-investigate").count() >= 1);
    }
}
