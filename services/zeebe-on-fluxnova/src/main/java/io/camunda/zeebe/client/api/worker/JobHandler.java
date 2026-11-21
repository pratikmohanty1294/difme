package io.camunda.zeebe.client.api.worker;

import io.camunda.zeebe.client.api.response.ActivatedJob;

/** Handles one job; an exception fails the job (its retries go down by one), as with Zeebe. */
@FunctionalInterface
public interface JobHandler {
    void handle(JobClient client, ActivatedJob job) throws Exception;
}
