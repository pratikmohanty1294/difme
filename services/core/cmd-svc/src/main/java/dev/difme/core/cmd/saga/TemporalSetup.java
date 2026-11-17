package dev.difme.core.cmd.saga;

import dev.difme.core.common.saga.PaymentActivities;
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

/** Temporal client + activity worker (PDF §7.1 step 6: Temporal → Command Svc "execute step"). */
@ApplicationScoped
public class TemporalSetup {
    @ConfigProperty(name = "difme.temporal.target") String target;
    @ConfigProperty(name = "difme.temporal.namespace") String namespace;
    @Inject PaymentActivitiesImpl activities;
    private WorkerFactory factory;

    @Produces @Singleton
    WorkflowClient client() {
        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    void start(@Observes StartupEvent ev, WorkflowClient client) {
        factory = WorkerFactory.newInstance(client);
        factory.newWorker(PaymentActivities.TASK_QUEUE).registerActivitiesImplementations(activities);
        factory.start();
    }

    void stop(@Observes ShutdownEvent ev) {
        if (factory != null) factory.shutdown();
    }
}
