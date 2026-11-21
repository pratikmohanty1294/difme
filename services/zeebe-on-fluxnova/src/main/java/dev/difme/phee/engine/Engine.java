package dev.difme.phee.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluxnova's engine REST API, the operations the Zeebe-shaped client needs. Variables cross as Fluxnova typed values:
 * strings, booleans and numbers keep their type; maps, lists and other objects travel as JSON.
 */
public final class Engine {
    public static final ObjectMapper JSON = new ObjectMapper();

    /** A locked external task (Zeebe's "activated job"). */
    public record Task(String id, String topic, String processInstanceId, String processDefinitionId, String processDefinitionKey,
                       String activityId, String activityInstanceId, String executionId, Integer retries, String workerId,
                       Map<String, Object> variables) {}

    /** The engine refused the request for good (4xx other than "nothing to correlate"). */
    public static final class Refused extends RuntimeException {
        public Refused(String m) { super(m); }
    }

    private final URI base;
    private final String auth;
    private final HttpClient http;

    /** baseUrl: the engine REST root, e.g. http://fluxnova:8080/engine-rest; user:password in the URL becomes Basic auth. */
    public Engine(String baseUrl) {
        URI u = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        String info = u.getRawUserInfo();
        this.auth = info == null ? null
                : "Basic " + Base64.getEncoder().encodeToString(java.net.URLDecoder.decode(info, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
        this.base = info == null ? u : URI.create(u.getScheme() + "://" + u.getHost() + (u.getPort() < 0 ? "" : ":" + u.getPort()) + u.getRawPath());
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    // ── external tasks ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Long-polls for up to max tasks of one topic (asyncResponseTimeout ms), locking them for lockMs. */
    public List<Task> fetchAndLock(String workerId, String topic, int max, long lockMs, long asyncResponseTimeoutMs, List<String> variables) {
        ObjectNode b = JSON.createObjectNode().put("workerId", workerId).put("maxTasks", max).put("usePriority", true)
                .put("asyncResponseTimeout", asyncResponseTimeoutMs);
        ObjectNode t0 = b.putArray("topics").addObject().put("topicName", topic).put("lockDuration", lockMs);
        if (variables != null && !variables.isEmpty()) variables.forEach(t0.putArray("variables")::add);
        JsonNode r = call("POST", "external-task/fetchAndLock", b, Duration.ofMillis(asyncResponseTimeoutMs + 10_000));
        List<Task> out = new ArrayList<>();
        for (JsonNode t : r) {
            out.add(new Task(t.path("id").asText(), t.path("topicName").asText(), t.path("processInstanceId").asText(),
                    t.path("processDefinitionId").asText(), t.path("processDefinitionKey").asText(), t.path("activityId").asText(),
                    t.path("activityInstanceId").asText(), t.path("executionId").asText(),
                    t.path("retries").isNull() || t.path("retries").isMissingNode() ? null : t.path("retries").asInt(),
                    workerId, fromTyped(t.path("variables"))));
        }
        return out;
    }

    public void complete(String taskId, String workerId, Map<String, Object> variables) {
        ObjectNode b = JSON.createObjectNode().put("workerId", workerId);
        b.set("variables", toTyped(variables));
        call("POST", "external-task/" + enc(taskId) + "/complete", b, null);
    }

    public void failure(String taskId, String workerId, String message, int retries, long retryTimeoutMs) {
        ObjectNode b = JSON.createObjectNode().put("workerId", workerId).put("errorMessage", trim(message))
                .put("retries", Math.max(0, retries)).put("retryTimeout", retryTimeoutMs);
        call("POST", "external-task/" + enc(taskId) + "/failure", b, null);
    }

    public void bpmnError(String taskId, String workerId, String code, String message, Map<String, Object> variables) {
        ObjectNode b = JSON.createObjectNode().put("workerId", workerId).put("errorCode", code).put("errorMessage", trim(message));
        b.set("variables", toTyped(variables));
        call("POST", "external-task/" + enc(taskId) + "/bpmnError", b, null);
    }

    public void setRetries(String taskId, int retries) {
        call("PUT", "external-task/" + enc(taskId) + "/retries", JSON.createObjectNode().put("retries", retries), null);
    }

    // ── process instances ──────────────────────────────────────────────────────────────────────────────────────────

    /** Starts the latest version of a process definition; returns [instanceId, definitionId]. */
    public String[] start(String processKey, Map<String, Object> variables) {
        ObjectNode b = JSON.createObjectNode();
        b.set("variables", toTyped(variables));
        JsonNode r = call("POST", "process-definition/key/" + enc(processKey) + "/start", b, null);
        return new String[] {r.path("id").asText(), r.path("definitionId").asText()};
    }

    /**
     * Correlates a message to the instance waiting for it whose correlation variable equals the key.
     * Returns false when no instance waits for it (yet).
     */
    public boolean correlate(String messageName, String correlationVariable, String key, Map<String, Object> variables) {
        ObjectNode b = JSON.createObjectNode().put("messageName", messageName).put("all", false);
        if (correlationVariable != null && key != null) {
            b.putObject("correlationKeys").putObject(correlationVariable).put("value", key).put("type", "String");
        }
        b.set("processVariables", toTyped(variables));
        try {
            call("POST", "message", b, null);
            return true;
        } catch (Refused e) {
            if (e.getMessage().contains("MismatchingMessageCorrelation") || e.getMessage().contains("No process definition or execution matches")) {
                return false;
            }
            throw e;
        }
    }

    /** Sets variables on an execution's scope (Zeebe's element instance), or on a whole process instance. */
    public void setVariables(String kind, String id, Map<String, Object> variables) {
        ObjectNode b = JSON.createObjectNode();
        b.set("modifications", toTyped(variables));
        call("POST", (kind.equals("instance") ? "process-instance/" : "execution/") + enc(id)
                + (kind.equals("instance") ? "/variables" : "/localVariables"), b, null);
    }

    public void deleteInstance(String processInstanceId) {
        call("DELETE", "process-instance/" + enc(processInstanceId), null, null);
    }

    // ── variables ──────────────────────────────────────────────────────────────────────────────────────────────────

    static ObjectNode toTyped(Map<String, Object> variables) {
        ObjectNode out = JSON.createObjectNode();
        if (variables == null) return out;
        variables.forEach((k, v) -> out.set(k, typed(v)));
        return out;
    }

    static ObjectNode typed(Object v) {
        ObjectNode n = JSON.createObjectNode();
        if (v == null) return n.putNull("value").put("type", "Null");
        if (v instanceof String s) return n.put("value", s).put("type", "String");
        if (v instanceof Boolean b) return n.put("value", b).put("type", "Boolean");
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return n.put("value", ((Number) v).intValue()).put("type", "Integer");
        if (v instanceof Long l) return n.put("value", l).put("type", "Long");
        if (v instanceof BigInteger bi) return n.put("value", bi.longValueExact()).put("type", "Long");
        if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) return n.put("value", ((Number) v).doubleValue()).put("type", "Double");
        if (v instanceof JsonNode j) {
            if (j.isTextual()) return n.put("value", j.asText()).put("type", "String");
            if (j.isBoolean()) return n.put("value", j.asBoolean()).put("type", "Boolean");
            if (j.isIntegralNumber()) return n.put("value", j.asLong()).put("type", "Long");
            if (j.isNumber()) return n.put("value", j.asDouble()).put("type", "Double");
            if (j.isNull()) return n.putNull("value").put("type", "Null");
            return n.put("value", j.toString()).put("type", "Json");
        }
        try {
            return n.put("value", JSON.writeValueAsString(v)).put("type", "Json");   // maps, lists, DTOs
        } catch (IOException e) {
            throw new IllegalArgumentException("variable not serialisable: " + v.getClass().getName(), e);
        }
    }

    static Map<String, Object> fromTyped(JsonNode vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (vars == null || !vars.isObject()) return out;
        Iterator<Map.Entry<String, JsonNode>> it = vars.fields();
        while (it.hasNext()) {
            var e = it.next();
            JsonNode t = e.getValue(), v = t.path("value");
            String type = t.path("type").asText("");
            Object value;
            if (v.isNull() || v.isMissingNode()) value = null;
            else if (type.equals("Json") || type.equals("Object") && v.isTextual() && looksJson(v.asText())) value = parse(v.asText());
            else if (v.isBoolean()) value = v.asBoolean();
            else if (v.isInt()) value = v.asInt();
            else if (v.isIntegralNumber()) value = v.asLong();
            else if (v.isNumber()) value = v.asDouble();
            else if (v.isTextual()) value = v.asText();
            else value = JSON.convertValue(v, Object.class);
            out.put(e.getKey(), value);
        }
        return out;
    }

    private static boolean looksJson(String s) {
        String t = s.strip();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static Object parse(String json) {
        try {
            return JSON.readValue(json, Object.class);
        } catch (IOException e) {
            return json;
        }
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private JsonNode call(String method, String path, ObjectNode body, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout == null ? Duration.ofSeconds(30) : timeout)
                .header("Accept", "application/json");
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("engine unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        String text = r.body() == null ? "" : r.body();
        if (r.statusCode() >= 200 && r.statusCode() < 300) {
            try {
                return text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
            } catch (IOException e) {
                return JSON.createObjectNode();
            }
        }
        if (r.statusCode() >= 500 && !text.contains("MismatchingMessageCorrelation")) {
            throw new IllegalStateException("engine HTTP " + r.statusCode() + " on " + path + ": " + trim(text));
        }
        throw new Refused("engine HTTP " + r.statusCode() + " on " + path + ": " + trim(text));
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 600 ? s.substring(0, 600) : s;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
