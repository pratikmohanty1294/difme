package dev.difme.process.bridge;

import dev.difme.process.fluxnova.FluxnovaClient;
import dev.difme.process.fluxnova.Variables;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * BPMN → Temporal (ADR-008): long-polls Fluxnova external tasks on topics {@code temporal:<WorkflowType>} and starts
 * that Temporal workflow. Workflow id = bpmn-&lt;processInstance&gt;-&lt;activity&gt;, so redelivery is idempotent.
 */
@ApplicationScoped
public class TemporalBridgeWorker {
    private static final Logger LOG = Logger.getLogger(TemporalBridgeWorker.class);

    @RestClient FluxnovaClient engine;
    WorkflowClient temporal;
    @ConfigProperty(name = "difme.bridge.enabled") boolean enabled;
    @ConfigProperty(name = "difme.bridge.worker-id") String workerId;
    @ConfigProperty(name = "difme.bridge.topics") List<String> topics;
    @ConfigProperty(name = "difme.bridge.lock-duration-ms") long lockMs;
    @ConfigProperty(name = "difme.bridge.long-poll-ms") long pollMs;
    private volatile boolean running;
    private Thread loop;

    public TemporalBridgeWorker(WorkflowClient temporal) {
        this.temporal = temporal;
    }

    void start(@Observes StartupEvent ev) {
        if (!enabled) return;
        running = true;
        loop = Thread.ofVirtual().name("temporal-bridge").start(this::run);
    }

    void stop(@Observes ShutdownEvent ev) {
        running = false;
        if (loop != null) loop.interrupt();
    }

    private void run() {
        var request = new FluxnovaClient.FetchAndLock(workerId, 10, true, pollMs,
                topics.stream().map(t -> new FluxnovaClient.Topic(t, lockMs)).toList());
        while (running) {
            try {
                for (FluxnovaClient.LockedTask task : engine.fetchAndLock(request)) handle(task);
            } catch (Exception e) {
                LOG.warnf(e, "fetchAndLock failed; retrying");
                sleep(2000);
            }
        }
    }

    void handle(FluxnovaClient.LockedTask task) {
        String workflowType = task.topicName().substring("temporal:".length());
        String workflowId = "bpmn-" + task.processInstanceId() + "-" + task.activityId();
        try {
            String queue = ConfigProvider.getConfig()
                    .getValue("difme.bridge.task-queue.\"" + workflowType + "\"", String.class);
            Map<String, Object> input = new HashMap<>(Variables.plain(task.variables()));
            input.put("processInstanceId", task.processInstanceId());
            input.put("businessKey", task.businessKey());
            try {
                temporal.newUntypedWorkflowStub(workflowType,
                        WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(queue).build()).start(input);
            } catch (WorkflowExecutionAlreadyStarted already) {
                // Redelivered task: the workflow is already running. Completing again is safe.
            }
            engine.complete(task.id(), new FluxnovaClient.Complete(workerId,
                    Variables.typed(Map.of("temporalWorkflowId", workflowId))));
        } catch (Exception e) {
            engine.failure(task.id(), new FluxnovaClient.Failure(workerId, e.getMessage(), 3, 10_000));
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
