package dev.difme.core.common.process;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Map;

/**
 * Temporal → Fluxnova callback (ADR-008). Any Temporal workflow started from a BPMN process reports back by
 * correlating a BPMN message to the waiting process instance. Hosted by process-gateway.
 */
@ActivityInterface
public interface ProcessCallbacks {
    String TASK_QUEUE = "process-callbacks";

    @ActivityMethod
    void correlate(String messageName, String processInstanceId, Map<String, Object> variables);
}
