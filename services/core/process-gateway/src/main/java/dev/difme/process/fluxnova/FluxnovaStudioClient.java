package dev.difme.process.fluxnova;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Read-side engine REST used by Process Studio and the task inbox. */
@RegisterRestClient(configKey = "fluxnova")
@ClientHeaderParam(name = "Authorization", value = "{basicAuth}")
public interface FluxnovaStudioClient {
    record Definition(String id, String key, String name, int version) {}
    record TaskDto(String id, String name, String assignee, String created, String formKey, String processDefinitionId,
                   String processInstanceId, String taskDefinitionKey) {}

    @GET @Path("/process-definition")
    List<Definition> processes(@QueryParam("latestVersion") Boolean latest);

    @GET @Path("/decision-definition")
    List<Definition> decisions(@QueryParam("latestVersion") Boolean latest);

    @GET @Path("/process-definition/key/{key}/xml")
    Map<String, String> processXml(@PathParam("key") String key);

    @GET @Path("/decision-definition/key/{key}/xml")
    Map<String, String> decisionXml(@PathParam("key") String key);

    @GET @Path("/task")
    List<TaskDto> tasks(@QueryParam("candidateGroups") String groups, @QueryParam("includeAssignedTasks") boolean assigned,
                        @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String order);

    default String basicAuth() {
        return EngineAuth.basic();
    }
}
