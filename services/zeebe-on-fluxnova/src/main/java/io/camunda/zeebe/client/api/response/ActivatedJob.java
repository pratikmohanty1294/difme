package io.camunda.zeebe.client.api.response;

import java.util.Map;

/** A job handed to a worker (a locked Fluxnova external task). Same shape as the Zeebe client's ActivatedJob. */
public interface ActivatedJob {
    long getKey();
    String getType();
    long getProcessInstanceKey();
    String getBpmnProcessId();
    int getProcessDefinitionVersion();
    long getProcessDefinitionKey();
    String getElementId();
    long getElementInstanceKey();
    Map<String, String> getCustomHeaders();
    String getWorker();
    int getRetries();
    long getDeadline();
    String getVariables();
    Map<String, Object> getVariablesAsMap();
    <T> T getVariablesAsType(Class<T> type);
}
