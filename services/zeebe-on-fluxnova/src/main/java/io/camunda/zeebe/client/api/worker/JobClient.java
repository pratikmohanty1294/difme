package io.camunda.zeebe.client.api.worker;

import dev.difme.phee.engine.Commands;

/** The job commands a handler may send. Same method names as the Zeebe client's JobClient. */
public interface JobClient {
    Commands.Complete newCompleteCommand(long jobKey);
    Commands.Fail newFailCommand(long jobKey);
    Commands.ThrowError newThrowErrorCommand(long jobKey);
}
