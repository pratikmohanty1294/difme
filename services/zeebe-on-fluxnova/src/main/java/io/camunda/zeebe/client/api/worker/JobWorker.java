package io.camunda.zeebe.client.api.worker;

/** A running worker: polls its job type until closed. */
public interface JobWorker extends AutoCloseable {
    boolean isOpen();
    boolean isClosed();
    @Override void close();
}
