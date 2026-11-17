package dev.difme.process.formio;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Form definition management on the self-hosted formio server (server-side API key; never exposed to browsers). */
@RegisterRestClient(configKey = "formio")
public interface FormioAdminClient {
    @GET @Path("/form")
    List<Map<String, Object>> list(@HeaderParam("x-token") String key, @QueryParam("type") String type,
                                   @QueryParam("select") String select, @QueryParam("limit") int limit);

    @POST @Path("/form")
    Map<String, Object> create(@HeaderParam("x-token") String key, Map<String, Object> form);

    @PUT @Path("/form/{id}")
    Map<String, Object> update(@HeaderParam("x-token") String key, @PathParam("id") String id, Map<String, Object> form);
}
