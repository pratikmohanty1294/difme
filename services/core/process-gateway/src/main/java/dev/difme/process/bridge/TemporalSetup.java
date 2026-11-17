package dev.difme.process.bridge;

import dev.difme.core.common.process.ProcessCallbacks;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TemporalSetup {
    @ConfigProperty(name = "difme.temporal.target") String target;
    @ConfigProperty(name = "difme.temporal.namespace") String namespace;
    @Inject ProcessCallbacksImpl callbacks;
    private WorkerFactory factory;

    @Produces @Singleton
    WorkflowClient client() {
        return WorkflowClient.newInstance(
                WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build()),
                WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    void start(@Observes StartupEvent ev, WorkflowClient client) {
        factory = WorkerFactory.newInstance(client);
        factory.newWorker(ProcessCallbacks.TASK_QUEUE).registerActivitiesImplementations(callbacks);
        factory.start();
    }

    void stop(@Observes ShutdownEvent ev) {
        if (factory != null) factory.shutdown();
    }
}
