package io.camunda.zeebe.client;

import dev.difme.phee.engine.Commands;
import dev.difme.phee.engine.FluxnovaZeebeClient;
import io.camunda.zeebe.client.api.worker.JobClient;

/**
 * The part of the Zeebe client API that Mifos Payment Hub EE uses, running on Fluxnova (dev.difme.phee.engine).
 * gatewayAddress is Fluxnova's engine REST root, e.g. http://fluxnova.process.svc:8080/engine-rest.
 */
public interface ZeebeClient extends JobClient, AutoCloseable {
    static ZeebeClientBuilder newClientBuilder() { return FluxnovaZeebeClient.builder(); }

    Commands.WorkerBuilder newWorker();
    Commands.CreateInstance newCreateInstanceCommand();
    Commands.PublishMessage newPublishMessageCommand();
    Commands.SetVariables newSetVariablesCommand(long elementInstanceKey);
    Commands.CancelInstance newCancelInstanceCommand(long processInstanceKey);
    Commands.UpdateRetries newUpdateRetriesCommand(long jobKey);
    Commands.ResolveIncident newResolveIncidentCommand(long incidentKey);

    @Override void close();
}
