package io.camunda.zeebe.client;

import java.time.Duration;

/** Builder with the Zeebe client's method names; settings without a Fluxnova meaning are accepted and ignored. */
public interface ZeebeClientBuilder {
    ZeebeClientBuilder gatewayAddress(String engineRestUrl);
    ZeebeClientBuilder usePlaintext();
    ZeebeClientBuilder defaultJobPollInterval(Duration interval);
    ZeebeClientBuilder defaultJobWorkerMaxJobsActive(int max);
    ZeebeClientBuilder numJobWorkerExecutionThreads(int threads);
    ZeebeClientBuilder defaultJobTimeout(Duration timeout);
    ZeebeClientBuilder defaultRequestTimeout(Duration timeout);
    ZeebeClient build();
}
