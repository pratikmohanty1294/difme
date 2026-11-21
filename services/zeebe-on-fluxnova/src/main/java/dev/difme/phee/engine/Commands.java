package dev.difme.phee.engine;

import io.camunda.zeebe.client.api.ZeebeFuture;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The fluent commands of the Zeebe client API, as PH-EE calls them, carried out on Fluxnova. */
public final class Commands {
    private Commands() {}

    /** A completed (or failed) send(). */
    public static final class Result<T> extends CompletableFuture<T> implements ZeebeFuture<T> {
        static <T> Result<T> of(Supplier<T> action) {
            Result<T> r = new Result<>();
            try {
                r.complete(action.get());
            } catch (RuntimeException e) {
                r.completeExceptionally(e);
            }
            return r;
        }
    }

    /** Variables as Zeebe accepts them: a map, a JSON string or object, or any object (serialised to JSON). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object variables) {
        if (variables == null) return Map.of();
        try {
            if (variables instanceof Map<?, ?> m) return (Map<String, Object>) m;
            if (variables instanceof String s) return Engine.JSON.readValue(s, Map.class);
            if (variables instanceof InputStream in) return Engine.JSON.readValue(in, Map.class);
            return Engine.JSON.convertValue(variables, Map.class);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("variables are not a JSON object", e);
        }
    }

    public static final class Complete {
        private final FluxnovaZeebeClient c;
        private final long key;
        private Map<String, Object> vars = Map.of();
        Complete(FluxnovaZeebeClient c, long key) { this.c = c; this.key = key; }
        public Complete variables(Object v) { vars = asMap(v); return this; }
        public Complete variable(String k, Object v) { vars = Map.of(k, v); return this; }
        public Complete requestTimeout(Duration d) { return this; }
        public Result<Object> send() { return Result.of(() -> { c.complete(key, vars); return null; }); }
    }

    public static final class Fail {
        private final FluxnovaZeebeClient c;
        private final long key;
        private int retries;
        private String message = "";
        private Duration backoff = Duration.ZERO;
        Fail(FluxnovaZeebeClient c, long key) { this.c = c; this.key = key; }
        public Fail retries(int r) { retries = r; return this; }
        public Fail errorMessage(String m) { message = m; return this; }
        public Fail retryBackoff(Duration d) { backoff = d; return this; }
        public Fail variables(Object v) { return this; }
        public Result<Object> send() { return Result.of(() -> { c.fail(key, retries, message, backoff.toMillis()); return null; }); }
    }

    public static final class ThrowError {
        private final FluxnovaZeebeClient c;
        private final long key;
        private String code = "", message = "";
        private Map<String, Object> vars = Map.of();
        ThrowError(FluxnovaZeebeClient c, long key) { this.c = c; this.key = key; }
        public ThrowError errorCode(String e) { code = e; return this; }
        public ThrowError errorMessage(String m) { message = m; return this; }
        public ThrowError variables(Object v) { vars = asMap(v); return this; }
        public Result<Object> send() { return Result.of(() -> { c.throwError(key, code, message, vars); return null; }); }
    }

    public static final class CreateInstance {
        private final FluxnovaZeebeClient c;
        private String processId;
        private Map<String, Object> vars = Map.of();
        CreateInstance(FluxnovaZeebeClient c) { this.c = c; }
        public CreateInstance bpmnProcessId(String id) { processId = id; return this; }
        public CreateInstance latestVersion() { return this; }   // Fluxnova starts the latest version by key
        public CreateInstance variables(Object v) { vars = asMap(v); return this; }
        public Result<ProcessInstanceEvent> send() { return Result.of(() -> c.start(processId, vars)); }
    }

    public static final class PublishMessage {
        private final FluxnovaZeebeClient c;
        private String name, correlationKey;
        private Duration ttl = Duration.ofHours(1);   // Zeebe's default time to live
        private Map<String, Object> vars = Map.of();
        PublishMessage(FluxnovaZeebeClient c) { this.c = c; }
        public PublishMessage messageName(String n) { name = n; return this; }
        public PublishMessage correlationKey(String k) { correlationKey = k; return this; }
        public PublishMessage timeToLive(Duration d) { ttl = d; return this; }
        public PublishMessage messageId(String id) { return this; }
        public PublishMessage variables(Object v) { vars = asMap(v); return this; }
        public Result<Object> send() { return Result.of(() -> { c.publish(name, correlationKey, ttl, vars); return null; }); }
    }

    public static final class SetVariables {
        private final FluxnovaZeebeClient c;
        private final long key;
        private Map<String, Object> vars = Map.of();
        SetVariables(FluxnovaZeebeClient c, long key) { this.c = c; this.key = key; }
        public SetVariables variables(Object v) { vars = asMap(v); return this; }
        public SetVariables local(boolean local) { return this; }
        public Result<Object> send() { return Result.of(() -> { c.setVariables(key, vars); return null; }); }
    }

    public static final class CancelInstance {
        private final FluxnovaZeebeClient c;
        private final long key;
        CancelInstance(FluxnovaZeebeClient c, long key) { this.c = c; this.key = key; }
        public Result<Object> send() { return Result.of(() -> { c.cancel(key); return null; }); }
    }

    public static final class UpdateRetries {
        private final FluxnovaZeebeClient c;
        private final long key;
        private int retries;
        UpdateRetries(FluxnovaZeebeClient c, long key) { this.c = c; this.key = key; }
        public UpdateRetries retries(int r) { retries = r; return this; }
        public Result<Object> send() { return Result.of(() -> { c.updateRetries(key, retries); return null; }); }
    }

    /** Fluxnova resolves a failed external task's incident when its retries are set again (UpdateRetries). */
    public static final class ResolveIncident {
        ResolveIncident() {}
        public Result<Object> send() { return Result.of(() -> null); }
    }

    public static final class WorkerBuilder {
        private final FluxnovaZeebeClient c;
        String type, name = "worker";
        JobHandler handler;
        int maxJobsActive;
        Duration timeout;
        List<String> fetchVariables;
        WorkerBuilder(FluxnovaZeebeClient c) { this.c = c; this.maxJobsActive = c.defaultMaxJobs; this.timeout = c.defaultTimeout; }
        public WorkerBuilder jobType(String t) { type = t; return this; }
        public WorkerBuilder handler(JobHandler h) { handler = h; return this; }
        public WorkerBuilder name(String n) { name = n; return this; }
        public WorkerBuilder maxJobsActive(int m) { maxJobsActive = Math.max(1, m); return this; }
        public WorkerBuilder timeout(Duration d) { timeout = d; return this; }
        public WorkerBuilder timeout(long ms) { timeout = Duration.ofMillis(ms); return this; }
        public WorkerBuilder pollInterval(Duration d) { return this; }
        public WorkerBuilder requestTimeout(Duration d) { return this; }
        public WorkerBuilder fetchVariables(List<String> v) { fetchVariables = v; return this; }
        public WorkerBuilder fetchVariables(String... v) { fetchVariables = List.of(v); return this; }
        public JobWorker open() {
            if (type == null || handler == null) throw new IllegalStateException("worker needs jobType and handler");
            return c.openWorker(this);
        }
    }
}
