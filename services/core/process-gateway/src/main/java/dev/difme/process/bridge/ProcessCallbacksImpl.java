package dev.difme.process.bridge;

import dev.difme.core.common.process.ProcessCallbacks;
import dev.difme.process.fluxnova.FluxnovaClient;
import dev.difme.process.fluxnova.Variables;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Temporal → Fluxnova: correlate a BPMN message to the waiting process instance. */
@ApplicationScoped
public class ProcessCallbacksImpl implements ProcessCallbacks {
    private final FluxnovaClient engine;

    public ProcessCallbacksImpl(@RestClient FluxnovaClient engine) {
        this.engine = engine;
    }

    @Override
    public void correlate(String messageName, String processInstanceId, Map<String, Object> variables) {
        engine.correlate(new FluxnovaClient.Correlate(messageName, processInstanceId, Variables.typed(variables)));
    }
}
