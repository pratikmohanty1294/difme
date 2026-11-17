package dev.difme.process.fluxnova;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Fluxnova engine REST API (basic auth enabled on the engine, ADR-008). */
@RegisterRestClient(configKey = "fluxnova")
@ClientHeaderParam(name = "Authorization", value = "{basicAuth}")
public interface FluxnovaClient {
    record TypedValue(Object value, String type) {}
    record DecisionDefinition(String id, String key, int version) {}
    record Topic(String topicName, long lockDuration) {}
    record FetchAndLock(String workerId, int maxTasks, boolean usePriority, long asyncResponseTimeout, List<Topic> topics) {}
    record LockedTask(String id, String topicName, String activityId, String processInstanceId, String businessKey,
                      Map<String, TypedValue> variables) {}
    record Complete(String workerId, Map<String, TypedValue> variables) {}
    record Failure(String workerId, String errorMessage, int retries, long retryTimeout) {}
    record Correlate(String messageName, String processInstanceId, Map<String, TypedValue> processVariables) {}
    record Evaluate(Map<String, TypedValue> variables) {}
    record StartProcess(String businessKey, Map<String, TypedValue> variables) {}
    record CompleteTask(Map<String, TypedValue> variables) {}

    @GET @Path("/decision-definition")
    List<DecisionDefinition> decisions(@QueryParam("key") String key, @QueryParam("version") Integer version);

    @POST @Path("/decision-definition/{id}/evaluate")
    List<Map<String, TypedValue>> evaluateById(@PathParam("id") String id, Evaluate body);

    @POST @Path("/decision-definition/key/{key}/evaluate")
    List<Map<String, TypedValue>> evaluateLatest(@PathParam("key") String key, Evaluate body);

    @POST @Path("/external-task/fetchAndLock")
    List<LockedTask> fetchAndLock(FetchAndLock body);

    @POST @Path("/external-task/{id}/complete")
    void complete(@PathParam("id") String id, Complete body);

    @POST @Path("/external-task/{id}/failure")
    void failure(@PathParam("id") String id, Failure body);

    @POST @Path("/message")
    void correlate(Correlate body);

    @POST @Path("/process-definition/key/{key}/start")
    Map<String, Object> start(@PathParam("key") String key, StartProcess body);

    @POST @Path("/task/{id}/complete")
    void completeTask(@PathParam("id") String id, CompleteTask body);

    default String basicAuth() {
        var c = ConfigProvider.getConfig();
        String raw = c.getValue("difme.fluxnova.user", String.class) + ":" + c.getOptionalValue("difme.fluxnova.password", String.class).orElse("");
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
