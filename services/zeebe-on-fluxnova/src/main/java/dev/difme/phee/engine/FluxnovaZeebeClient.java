package dev.difme.phee.engine;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.ZeebeClientBuilder;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.client.api.worker.JobWorker;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Zeebe client semantics on Fluxnova: job workers long-poll external tasks of their job type (topic); a handler that
 * throws fails the job (retries − 1; at zero the engine raises an incident); process start, message publication and
 * variables map to the engine REST API. Zeebe's 64-bit keys are issued here for Fluxnova's string ids.
 */
public final class FluxnovaZeebeClient implements ZeebeClient {
    private static final Logger LOG = Logger.getLogger(FluxnovaZeebeClient.class.getName());

    /** What a key stands for in Fluxnova. */
    record Ref(String kind, String id, String workerId) {}

    final Engine engine;
    final int defaultMaxJobs;
    final Duration defaultTimeout;
    private final ExecutorService handlers;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "zeebe-fluxnova-messages"));
    private final List<Worker> workers = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> messageVariables;
    private final AtomicLong next = new AtomicLong(2_251_799_813_685_248L);   // Zeebe-like key range
    private final Map<Long, Ref> refs = new ConcurrentHashMap<>();
    private final Map<String, Long> keysById = new ConcurrentHashMap<>();
    private final String host;

    FluxnovaZeebeClient(String url, int maxJobs, int threads, Duration timeout) {
        this.engine = new Engine(url);
        this.defaultMaxJobs = maxJobs;
        this.defaultTimeout = timeout;
        this.handlers = Executors.newFixedThreadPool(Math.max(1, threads), r -> daemon(r, "zeebe-fluxnova-handler"));
        this.messageVariables = loadMessageVariables();
        String h;
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            h = "local";
        }
        this.host = h;
    }

    public static ZeebeClientBuilder builder() { return new Builder(); }

    // ── keys ───────────────────────────────────────────────────────────────────────────────────────────────────────

    long keyFor(String kind, String id, String workerId) {
        Long k = keysById.computeIfAbsent(kind + ":" + id, x -> next.incrementAndGet());
        refs.put(k, new Ref(kind, id, workerId));
        if (refs.size() > 500_000) prune();
        return k;
    }

    private synchronized void prune() {
        long floor = next.get() - 250_000;      // keep the newest quarter million
        refs.keySet().removeIf(k -> k < floor);
        keysById.values().removeIf(k -> k < floor);
    }

    private Ref ref(long key, String kind) {
        Ref r = refs.get(key);
        if (r == null || kind != null && !r.kind().equals(kind)) {
            throw new IllegalArgumentException("unknown " + (kind == null ? "" : kind + " ") + "key " + key + " (expired or from before a restart)");
        }
        return r;
    }

    // ── commands ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Override public Commands.Complete newCompleteCommand(long jobKey) { return new Commands.Complete(this, jobKey); }
    @Override public Commands.Fail newFailCommand(long jobKey) { return new Commands.Fail(this, jobKey); }
    @Override public Commands.ThrowError newThrowErrorCommand(long jobKey) { return new Commands.ThrowError(this, jobKey); }
    @Override public Commands.WorkerBuilder newWorker() { return new Commands.WorkerBuilder(this); }
    @Override public Commands.CreateInstance newCreateInstanceCommand() { return new Commands.CreateInstance(this); }
    @Override public Commands.PublishMessage newPublishMessageCommand() { return new Commands.PublishMessage(this); }
    @Override public Commands.SetVariables newSetVariablesCommand(long key) { return new Commands.SetVariables(this, key); }
    @Override public Commands.CancelInstance newCancelInstanceCommand(long key) { return new Commands.CancelInstance(this, key); }
    @Override public Commands.UpdateRetries newUpdateRetriesCommand(long jobKey) { return new Commands.UpdateRetries(this, jobKey); }
    @Override public Commands.ResolveIncident newResolveIncidentCommand(long incidentKey) { return new Commands.ResolveIncident(); }

    void complete(long jobKey, Map<String, Object> vars) {
        Ref r = ref(jobKey, "task");
        engine.complete(r.id(), r.workerId(), vars);
        forget(jobKey);
    }

    void fail(long jobKey, int retries, String message, long backoffMs) {
        Ref r = ref(jobKey, "task");
        engine.failure(r.id(), r.workerId(), message, retries, backoffMs);
        forget(jobKey);
    }

    void throwError(long jobKey, String code, String message, Map<String, Object> vars) {
        Ref r = ref(jobKey, "task");
        engine.bpmnError(r.id(), r.workerId(), code, message, vars);
        forget(jobKey);
    }

    void updateRetries(long jobKey, int retries) {
        engine.setRetries(ref(jobKey, "task").id(), retries);
    }

    ProcessInstanceEvent start(String processId, Map<String, Object> vars) {
        String[] r = engine.start(processId, vars);
        long instanceKey = keyFor("instance", r[0], null), definitionKey = keyFor("definition", r[1], null);
        int version = versionOf(r[1]);
        return new ProcessInstanceEvent() {
            public long getProcessDefinitionKey() { return definitionKey; }
            public String getBpmnProcessId() { return processId; }
            public int getVersion() { return version; }
            public long getProcessInstanceKey() { return instanceKey; }
            @Override public String toString() { return "ProcessInstance[" + processId + " " + r[0] + "]"; }
        };
    }

    /**
     * Correlates now if an instance waits for the message; otherwise keeps trying until its time to live runs out,
     * as Zeebe buffers a message for instances that reach the catch event later.
     */
    void publish(String name, String correlationKey, Duration ttl, Map<String, Object> vars) {
        if (correlate(name, correlationKey, vars)) return;
        long deadline = System.currentTimeMillis() + ttl.toMillis();
        if (ttl.isZero() || ttl.isNegative()) return;    // Zeebe drops an unbuffered message nobody waits for
        scheduler.schedule(new Runnable() {
            long delay = 100;
            public void run() {
                try {
                    if (correlate(name, correlationKey, vars)) return;
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "message " + name + "/" + correlationKey + ": " + e.getMessage());
                }
                if (System.currentTimeMillis() + delay > deadline) {
                    LOG.info("message " + name + "/" + correlationKey + " expired without a waiting instance");
                    return;
                }
                delay = Math.min(delay * 2, 2_000);
                scheduler.schedule(this, delay, TimeUnit.MILLISECONDS);
            }
        }, 100, TimeUnit.MILLISECONDS);
    }

    /** Tries each variable the flows correlate this message on (ids are unique, so a wrong one matches nothing). */
    private boolean correlate(String name, String correlationKey, Map<String, Object> vars) {
        for (String variable : messageVariables.getOrDefault(name, List.of("transactionId"))) {
            if (engine.correlate(name, variable, correlationKey, vars)) return true;
        }
        return false;
    }

    void setVariables(long key, Map<String, Object> vars) {
        Ref r = refs.get(key);
        if (r == null) throw new IllegalArgumentException("unknown key " + key);
        if (r.kind().equals("task")) engine.setVariables("execution", executionOf(r), vars);
        else engine.setVariables(r.kind().equals("instance") ? "instance" : "execution", r.id(), vars);
    }

    void cancel(long instanceKey) {
        engine.deleteInstance(ref(instanceKey, "instance").id());
    }

    private final Map<String, String> executions = new ConcurrentHashMap<>();   // task id -> execution id

    private String executionOf(Ref task) {
        String e = executions.get(task.id());
        if (e == null) throw new IllegalArgumentException("no execution known for task " + task.id());
        return e;
    }

    private void forget(long jobKey) {
        Ref r = refs.remove(jobKey);
        if (r != null) {
            keysById.remove("task:" + r.id());
            executions.remove(r.id());
        }
    }

    // ── workers ────────────────────────────────────────────────────────────────────────────────────────────────────

    JobWorker openWorker(Commands.WorkerBuilder b) {
        Worker w = new Worker(b);
        workers.add(w);
        w.thread.start();
        return w;
    }

    private final class Worker implements JobWorker {
        final Commands.WorkerBuilder b;
        final Semaphore slots;
        final String workerId;
        final Thread thread;
        volatile boolean open = true;

        Worker(Commands.WorkerBuilder b) {
            this.b = b;
            this.slots = new Semaphore(b.maxJobsActive);
            this.workerId = b.name + "@" + host;
            this.thread = new Thread(this::poll, "zeebe-fluxnova-worker-" + b.type);
            this.thread.setDaemon(true);
        }

        void poll() {
            long backoff = 200;
            while (open) {
                int free = slots.availablePermits();
                if (free == 0) {
                    sleep(20);
                    continue;
                }
                try {
                    List<Engine.Task> tasks = engine.fetchAndLock(workerId, b.type, free, b.timeout.toMillis(), 10_000, b.fetchVariables);
                    backoff = 200;
                    for (Engine.Task t : tasks) {
                        slots.acquireUninterruptibly();
                        handlers.execute(() -> run(t));
                    }
                } catch (RuntimeException e) {
                    if (!open) return;
                    LOG.log(Level.WARNING, "worker " + b.type + ": " + e.getMessage());
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, 10_000);
                }
            }
        }

        void run(Engine.Task t) {
            long key = keyFor("task", t.id(), workerId);
            executions.put(t.id(), t.executionId());
            try {
                b.handler.handle(FluxnovaZeebeClient.this, job(key, t, b.timeout));
            } catch (Exception e) {
                // Zeebe's default: a handler exception fails the job with one retry fewer.
                int retries = (t.retries() == null ? 3 : t.retries()) - 1;
                LOG.log(Level.WARNING, "job " + b.type + " " + t.id() + " failed (retries left " + retries + "): " + e);
                try {
                    engine.failure(t.id(), workerId, String.valueOf(e.getMessage()), retries, 0);
                    forget(key);
                } catch (RuntimeException f) {
                    LOG.log(Level.WARNING, "could not report failure of " + t.id() + ": " + f.getMessage());
                }
            } finally {
                slots.release();
            }
        }

        public boolean isOpen() { return open; }
        public boolean isClosed() { return !open; }
        public void close() { open = false; thread.interrupt(); }
    }

    private ActivatedJob job(long key, Engine.Task t, Duration timeout) {
        long instanceKey = keyFor("instance", t.processInstanceId(), null);
        long definitionKey = keyFor("definition", t.processDefinitionId(), null);
        long elementKey = keyFor("execution", t.executionId(), null);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        Map<String, Object> vars = t.variables();
        return new ActivatedJob() {
            public long getKey() { return key; }
            public String getType() { return t.topic(); }
            public long getProcessInstanceKey() { return instanceKey; }
            public String getBpmnProcessId() { return t.processDefinitionKey(); }
            public int getProcessDefinitionVersion() { return versionOf(t.processDefinitionId()); }
            public long getProcessDefinitionKey() { return definitionKey; }
            public String getElementId() { return t.activityId(); }
            public long getElementInstanceKey() { return elementKey; }
            public Map<String, String> getCustomHeaders() { return Map.of(); }
            public String getWorker() { return t.workerId(); }
            public int getRetries() { return t.retries() == null ? 3 : t.retries(); }
            public long getDeadline() { return deadline; }
            public String getVariables() {
                try {
                    return Engine.JSON.writeValueAsString(vars);
                } catch (IOException e) {
                    return "{}";
                }
            }
            public Map<String, Object> getVariablesAsMap() { return new LinkedHashMap<>(vars); }
            public <T> T getVariablesAsType(Class<T> type) { return Engine.JSON.convertValue(vars, type); }
            @Override public String toString() { return "Job[" + t.topic() + " " + t.id() + " in " + t.processDefinitionKey() + "]"; }
        };
    }

    /** Fluxnova definition ids look like "key:version:uuid". */
    static int versionOf(String definitionId) {
        String[] p = definitionId == null ? new String[0] : definitionId.split(":");
        try {
            return p.length >= 2 ? Integer.parseInt(p[1]) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Message name → the process variable its correlation key is matched against. Generated by the BPMN converter
     * from each flow's zeebe:subscription (classpath resource zeebe-on-fluxnova-messages.properties); messages not listed
     * correlate on transactionId, PH-EE's usual key.
     */
    static Map<String, List<String>> loadMessageVariables() {
        Map<String, List<String>> m = new ConcurrentHashMap<>();
        try (InputStream in = FluxnovaZeebeClient.class.getClassLoader().getResourceAsStream("zeebe-on-fluxnova-messages.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                p.forEach((k, v) -> m.put(k.toString(), List.of(v.toString().split("\\s*,\\s*"))));
            }
        } catch (IOException e) {
            LOG.warning("cannot read zeebe-on-fluxnova-messages.properties: " + e.getMessage());
        }
        String extra = System.getenv("ZEEBE_FLUXNOVA_MESSAGES");      // name=var|var,name=var
        if (extra != null) {
            for (String pair : extra.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) m.put(kv[0].strip(), List.of(kv[1].strip().split("\\s*\\|\\s*")));
            }
        }
        return m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @Override
    public void close() {
        workers.forEach(Worker::close);
        handlers.shutdown();
        scheduler.shutdownNow();
    }

    static final class Builder implements ZeebeClientBuilder {
        private String url = "http://localhost:8080/engine-rest";
        private int maxJobs = 32, threads = 4;
        private Duration timeout = Duration.ofMinutes(5);

        public ZeebeClientBuilder gatewayAddress(String a) {
            url = a.startsWith("http://") || a.startsWith("https://") ? a : "http://" + a + "/engine-rest";
            return this;
        }
        public ZeebeClientBuilder usePlaintext() { return this; }
        public ZeebeClientBuilder defaultJobPollInterval(Duration d) { return this; }
        public ZeebeClientBuilder defaultJobWorkerMaxJobsActive(int m) { maxJobs = Math.max(1, Math.min(m, 256)); return this; }
        public ZeebeClientBuilder numJobWorkerExecutionThreads(int t) { threads = Math.max(1, t); return this; }
        public ZeebeClientBuilder defaultJobTimeout(Duration d) { timeout = d; return this; }
        public ZeebeClientBuilder defaultRequestTimeout(Duration d) { return this; }
        public ZeebeClient build() { return new FluxnovaZeebeClient(url, maxJobs, threads, timeout); }
    }
}
