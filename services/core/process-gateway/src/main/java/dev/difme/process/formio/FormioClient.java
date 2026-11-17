package dev.difme.process.formio;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Self-hosted formio server (ADR-008). Authenticated with a server API key. */
@RegisterRestClient(configKey = "formio")
public interface FormioClient {
    @GET @Path("/{formPath}")
    Map<String, Object> form(@PathParam("formPath") String formPath, @HeaderParam("x-token") String apiKey);

    @POST @Path("/{formPath}/submission")
    Map<String, Object> submit(@PathParam("formPath") String formPath, @HeaderParam("x-token") String apiKey, Map<String, Object> body);
}
