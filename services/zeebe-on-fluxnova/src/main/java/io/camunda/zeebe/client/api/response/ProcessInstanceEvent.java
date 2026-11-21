package io.camunda.zeebe.client.api.response;

/** A started process instance. Same shape as the Zeebe client's ProcessInstanceEvent. */
public interface ProcessInstanceEvent {
    long getProcessDefinitionKey();
    String getBpmnProcessId();
    int getVersion();
    long getProcessInstanceKey();
}
