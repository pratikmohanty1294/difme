package dev.difme.core.cmd.ratelimit;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Calls rate-limiter :8084 (PN-counter CRDT on Valkey, PDF §5.3). */
@RegisterRestClient(configKey = "rate-limiter")
@Path("/v1/acquire")
public interface RateLimiterClient {
    record Acquire(String subject, String scope, long eventTimeMs) {}
    record Decision(boolean allowed, long current) {}

    @POST
    Decision acquire(Acquire request);
}
