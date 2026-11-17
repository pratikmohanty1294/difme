package dev.difme.core.saga;

import dev.difme.core.common.saga.PaymentWorkflow;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class WorkerSetup {
    @ConfigProperty(name = "difme.temporal.target") String target;
    @ConfigProperty(name = "difme.temporal.namespace") String namespace;
    private WorkerFactory factory;

    void start(@Observes StartupEvent ev) {
        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        WorkflowClient client = WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
        factory = WorkerFactory.newInstance(client);
        factory.newWorker(PaymentWorkflow.TASK_QUEUE).registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        factory.start();
    }

    void stop(@Observes ShutdownEvent ev) {
        if (factory != null) factory.shutdown();
    }
}
