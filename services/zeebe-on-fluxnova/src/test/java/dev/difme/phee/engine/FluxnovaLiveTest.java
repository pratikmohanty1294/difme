package dev.difme.phee.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Zeebe-shaped client against a real Fluxnova (FLUXNOVA_URL, e.g. http://127.0.0.1:18090/engine-rest), running a
 * flow written in Payment Hub EE's Zeebe dialect and converted by ci/zeebe-bpmn-to-fluxnova.py (PORT_CHECK_BPMN).
 * The calls below are the ones PH-EE's connectors make. Skipped when FLUXNOVA_URL is not set.
 */
class FluxnovaLiveTest {
    static final String URL = System.getenv("FLUXNOVA_URL");
    static ZeebeClient zeebe;
    static final List<String> seen = new CopyOnWriteArrayList<>();
    static final AtomicInteger blockCalls = new AtomicInteger();
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(URL != null, "FLUXNOVA_URL not set");
        deploy(Path.of(System.getenv("PORT_CHECK_BPMN")));
        // Built exactly like PH-EE's ZeebeClientConfiguration does it.
        zeebe = ZeebeClient.newClientBuilder().gatewayAddress(URL).usePlaintext()
                .defaultJobPollInterval(Duration.ofMillis(1)).defaultJobWorkerMaxJobsActive(2000).numJobWorkerExecutionThreads(4).build();
        // Like PH-EE's block-funds worker: reads variables, completes with new ones; the first call for "flaky" throws.
        zeebe.newWorker().jobType("check-block-t1").handler((client, job) -> {
            Map<String, Object> v = job.getVariablesAsMap();
            if ("flaky".equals(v.get("scenario")) && blockCalls.incrementAndGet() == 1) throw new IllegalStateException("AMS timeout");
            seen.add("block " + v.get("transactionId") + " retries=" + job.getRetries() + " type=" + job.getType() + " bpmn=" + job.getBpmnProcessId());
            client.newCompleteCommand(job.getKey()).variables(Map.of("transferPrepareFailed", "fail".equals(v.get("scenario")),
                    "transferCode", Map.of("code", "TC-" + v.get("transactionId"), "legs", List.of(1, 2)))).send().join();
        }).name("block-funds").maxJobsActive(10).open();
        zeebe.newWorker().jobType("check-book-t1").handler((client, job) -> {
            Map<String, Object> v = job.getVariablesAsMap();
            seen.add("book " + v.get("transactionId") + " code=" + ((Map<?, ?>) v.get("transferCode")).get("code") + " state=" + v.get("transferState"));
            client.newCompleteCommand(job.getKey()).send().join();
        }).name("book-funds").maxJobsActive(10).open();
    }

    @AfterAll
    static void tearDown() { if (zeebe != null) zeebe.close(); }

    private static ProcessInstanceEvent start(String tx, long amount, String scenario, String timer) {
        // As PH-EE's ZeebeProcessStarter.
        return zeebe.newCreateInstanceCommand().bpmnProcessId("port-check-t1").latestVersion()
                .variables(Map.of("transactionId", tx, "amount", amount, "scenario", scenario, "timer", timer)).send().join();
    }

    @Test
    void messageArrivingEarlyIsBufferedAndTheFlowCompletesOnTheFeelBranch() throws Exception {
        String tx = UUID.randomUUID().toString();
        ProcessInstanceEvent pi = start(tx, 500, "ok", "PT1M");
        assertEquals("port-check-t1", pi.getBpmnProcessId());
        assertTrue(pi.getProcessInstanceKey() > 0);
        // Published right away, as PH-EE's channel does: the instance may not wait yet, so the message is held until it does.
        zeebe.newPublishMessageCommand().messageName("transferResponse").correlationKey(tx).timeToLive(Duration.ofSeconds(30))
                .variables(Map.of("transferState", "COMMITTED")).send().join();
        assertEquals("bigEnd", awaitEnd(tx));
        assertTrue(seen.contains("book " + tx + " code=TC-" + tx + " state=COMMITTED"), seen.toString());
        assertTrue(seen.stream().anyMatch(s -> s.startsWith("block " + tx) && s.contains("type=check-block-t1") && s.contains("bpmn=port-check-t1")));
    }

    @Test
    void feelConditionsPickTheOtherBranches() throws Exception {
        String failed = UUID.randomUUID().toString();
        start(failed, 500, "fail", "PT1M");
        assertEquals("failedEnd", awaitEnd(failed));

        String small = UUID.randomUUID().toString();
        start(small, 50, "ok", "PT1M");
        zeebe.newPublishMessageCommand().messageName("transferResponse").correlationKey(small).timeToLive(Duration.ofSeconds(30))
                .variables(Map.of("transferState", "COMMITTED")).send().join();
        assertEquals("smallEnd", awaitEnd(small));
    }

    @Test
    void timerFromAVariableFiresWhenNoResponseComes() throws Exception {
        String tx = UUID.randomUUID().toString();
        start(tx, 500, "ok", "PT2S");
        assertEquals("timedOutEnd", awaitEnd(tx));
    }

    @Test
    void aThrowingHandlerIsRetriedWithOneRetryFewer() throws Exception {
        String tx = UUID.randomUUID().toString();
        start(tx, 500, "flaky", "PT1M");
        zeebe.newPublishMessageCommand().messageName("transferResponse").correlationKey(tx).timeToLive(Duration.ofSeconds(30))
                .variables(Map.of("transferState", "COMMITTED")).send().join();
        assertEquals("bigEnd", awaitEnd(tx));
        assertTrue(seen.contains("block " + tx + " retries=2 type=check-block-t1 bpmn=port-check-t1"), seen.toString());
    }

    @Test
    void cancellingAnInstanceByItsKey() throws Exception {
        String tx = UUID.randomUUID().toString();
        ProcessInstanceEvent pi = start(tx, 500, "ok", "PT1M");
        waitUntil(() -> activityReached(tx, "waitResponse"));
        zeebe.newCancelInstanceCommand(pi.getProcessInstanceKey()).send().join();
        JsonNode h = history(tx);
        assertEquals("INTERNALLY_TERMINATED".equals(h.path("state").asText()) || "EXTERNALLY_TERMINATED".equals(h.path("state").asText()), true, h.toString());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────────

    interface Check { boolean ok() throws Exception; }

    static void waitUntil(Check c) throws Exception {
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return;
            Thread.sleep(200);
        }
        fail("condition not reached in 30 s");
    }

    static JsonNode history(String tx) throws Exception {
        String q = URL + "/history/process-instance?processDefinitionKey=port-check-t1&variables=transactionId_eq_" + tx;
        JsonNode list = Engine.JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(q)).build(), HttpResponse.BodyHandlers.ofString()).body());
        return list.size() == 0 ? Engine.JSON.createObjectNode() : list.get(0);
    }

    static boolean activityReached(String tx, String activity) throws Exception {
        String id = history(tx).path("id").asText("");
        if (id.isEmpty()) return false;
        String q = URL + "/history/activity-instance?processInstanceId=" + id + "&activityId=" + activity;
        return Engine.JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(q)).build(), HttpResponse.BodyHandlers.ofString()).body()).size() > 0;
    }

    /** Waits for the instance to finish and returns the end event it finished at. */
    static String awaitEnd(String tx) throws Exception {
        waitUntil(() -> "COMPLETED".equals(history(tx).path("state").asText()));
        String id = history(tx).path("id").asText();
        JsonNode acts = Engine.JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(URL + "/history/activity-instance?processInstanceId=" + id
                + "&activityType=noneEndEvent")).build(), HttpResponse.BodyHandlers.ofString()).body());
        return acts.get(0).path("activityId").asText();
    }

    static void deploy(Path bpmn) throws Exception {
        String boundary = "----difme" + UUID.randomUUID();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"deployment-name\"\r\n\r\nport-check\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"deploy-changed-only\"\r\n\r\ntrue\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"data\"; filename=\"" + bpmn.getFileName() + "\"\r\n"
                + "Content-Type: application/xml\r\n\r\n";
        byte[] body = concat(head.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(bpmn), ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(URL + "/deployment/create"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, i, p.length); i += p.length; }
        return out;
    }
}
