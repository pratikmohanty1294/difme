package dev.difme.core.cmd.saga;

import dev.difme.core.common.process.AccountActivities;
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

/** Temporal client + activity workers (PDF §7.1 step 6; ADR-008 account provisioning). */
@ApplicationScoped
public class TemporalSetup {
    @ConfigProperty(name = "difme.temporal.target") String target;
    @ConfigProperty(name = "difme.temporal.namespace") String namespace;
    @Inject PaymentActivitiesImpl activities;
    @Inject AccountActivitiesImpl accountActivities;
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
        factory.newWorker(AccountActivities.TASK_QUEUE).registerActivitiesImplementations(accountActivities);
        factory.start();
    }

    void stop(@Observes ShutdownEvent ev) {
        if (factory != null) factory.shutdown();
    }
}
