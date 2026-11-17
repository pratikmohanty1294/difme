package dev.difme.process.api;

import dev.difme.process.fluxnova.Deployer;
import dev.difme.process.fluxnova.FluxnovaStudioClient;
import dev.difme.process.formio.FormioAdminClient;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Backend for Process Studio, the Form Builder and the task inbox (ADR-008/010). Engine and formio credentials stay server-side. */
@Path("/v1")
@RunOnVirtualThread
public class StudioResource {
    public record ModelSummary(String type, String key, String name, int version) {}
    public record DeployRequest(String name, List<Deployer.File> files) {}
    public record TaskView(String id, String name, String processDefinitionKey, String created, String formKey, String assignee) {}

    @Inject @RestClient FluxnovaStudioClient engine;
    @Inject @RestClient FormioAdminClient formsAdmin;
    @Inject @RestClient dev.difme.process.formio.FormioClient forms;
    @Inject Deployer deployer;
    @ConfigProperty(name = "difme.formio.api-key") String formioKey;

    @GET @Path("/studio/models")
    public List<ModelSummary> models() {
        return Stream.concat(
                engine.processes(true).stream().map(d -> new ModelSummary("bpmn", d.key(), d.name(), d.version())),
                engine.decisions(true).stream().map(d -> new ModelSummary("dmn", d.key(), d.name(), d.version())))
                .sorted(Comparator.comparing(ModelSummary::key)).toList();
    }

    @GET @Path("/studio/decisions")
    public List<ModelSummary> decisionVersions() {
        return engine.decisions(null).stream().map(d -> new ModelSummary("dmn", d.key(), d.name(), d.version()))
                .sorted(Comparator.comparing(ModelSummary::key).thenComparing(ModelSummary::version)).toList();
    }

    @GET @Path("/studio/models/{type}/{key}")
    public Map<String, String> model(@PathParam("type") String type, @PathParam("key") String key) {
        return switch (type) {
            case "bpmn" -> Map.of("xml", engine.processXml(key).get("bpmn20Xml"));
            case "dmn" -> Map.of("xml", engine.decisionXml(key).get("dmnXml"));
            default -> throw new jakarta.ws.rs.NotFoundException();
        };
    }

    @POST @Path("/studio/deploy")
    public Map<String, Object> deploy(@HeaderParam("Authorization") String userinfo, DeployRequest req) throws Exception {
        Caller.from(userinfo).require("process-designers");
        return deployer.deploy(req.name(), req.files());
    }

    @GET @Path("/studio/forms")
    public List<Map<String, Object>> forms() {
        return formsAdmin.list(formioKey, "form", "title,path", 500);
    }

    @GET @Path("/studio/forms/{path}")
    public Map<String, Object> form(@PathParam("path") String path) {
        return forms.form(path, formioKey);
    }

    @PUT @Path("/studio/forms")
    public Response saveForm(@HeaderParam("Authorization") String userinfo, Map<String, Object> form) {
        Caller.from(userinfo).require("process-designers");
        String path = String.valueOf(form.get("path"));
        if (!path.matches("[a-z0-9-]{2,64}")) return Response.status(422).entity(Map.of("detail", "invalid path")).build();
        Map<String, Object> existing = null;
        try { existing = forms.form(path, formioKey); } catch (jakarta.ws.rs.WebApplicationException notFound) { /* new form */ }
        Map<String, Object> saved = existing == null ? formsAdmin.create(formioKey, form)
                : formsAdmin.update(formioKey, String.valueOf(existing.get("_id")), form);
        return Response.ok(Map.of("path", saved.get("path"), "id", saved.get("_id"))).build();
    }

    @GET @Path("/tasks")
    public List<TaskView> tasks(@HeaderParam("Authorization") String userinfo, @QueryParam("groups") String requested) {
        Caller caller = Caller.from(userinfo);
        List<String> groups = new ArrayList<>(caller.groups());
        if (requested != null && !requested.isBlank()) groups.retainAll(List.of(requested.split(",")));
        if (groups.isEmpty()) return List.of();
        return engine.tasks(String.join(",", groups), true, "created", "desc").stream()
                .map(t -> new TaskView(t.id(), t.name(), t.processDefinitionId().split(":")[0], t.created(), t.formKey(), t.assignee()))
                .toList();
    }
}
