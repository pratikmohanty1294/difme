package dev.difme.core.router;

import dev.difme.core.consistency.Backend;
import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.RoutingMatrix;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/** In JVM services routing happens in-process via the @ConsistencyProfile interceptor; this API serves everyone else. */
@Path("/v1/routes")
public class RoutesResource {
    @GET
    public Map<Consistency, Backend> all() {
        return RoutingMatrix.all();
    }

    @GET @Path("/{level}")
    public Response route(@PathParam("level") String level) {
        try {
            Consistency c = Consistency.valueOf(level.toUpperCase());
            return Response.ok(Map.of("consistency", c, "backend", RoutingMatrix.route(c),
                    "client", RoutingMatrix.route(c).clientName)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(404).build();
        }
    }
}
