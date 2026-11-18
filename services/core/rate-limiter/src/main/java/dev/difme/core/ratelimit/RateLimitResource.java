package dev.difme.core.ratelimit;

import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.ConsistencyProfile;
import io.quarkus.redis.datasource.RedisDataSource;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.redis.client.Response;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** POST /v1/acquire — CRDT sliding-window limiter. Window position comes from the caller's event time (replayable). */
@Path("/v1/acquire")
@jakarta.inject.Singleton
@io.quarkus.runtime.Startup   // load the script at boot: a missing resource fails startup (readiness), not the first request
@RunOnVirtualThread
public class RateLimitResource {
    public record Acquire(String subject, String scope, long eventTimeMs) {}
    public record Decision(boolean allowed, long current) {}

    @Inject RedisDataSource redis;
    @ConfigProperty(name = "difme.region") String region;
    @ConfigProperty(name = "difme.rate.window") Duration window;
    private String script;

    @PostConstruct
    void load() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/pn_counter.lua")) {
            if (in == null) throw new IllegalStateException("pn_counter.lua missing from the image (quarkus.native.resources.includes)");
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @POST
    @ConsistencyProfile(Consistency.CRDT)
    public Decision acquire(Acquire a) {
        long windowMs = window.toMillis();
        long bucket = a.eventTimeMs() / windowMs;
        double elapsed = (a.eventTimeMs() % windowMs) / (double) windowMs;
        long limit = limitFor(a.scope());
        String cur = "rl:{" + a.subject() + "}:" + a.scope() + ":" + bucket;
        String prev = "rl:{" + a.subject() + "}:" + a.scope() + ":" + (bucket - 1);
        Response r = redis.execute("EVAL", script, "2", cur, prev, region, Long.toString(limit),
                Long.toString(Math.max(1, windowMs / 1000)), Double.toString(elapsed), "1");
        return new Decision(r.get(0).toInteger() == 1, r.get(1).toLong());
    }

    private long limitFor(String scope) {
        return ConfigProvider.getConfig().getOptionalValue("difme.rate.limits." + scope, Long.class)
                .orElseGet(() -> ConfigProvider.getConfig().getValue("difme.rate.limits.default", Long.class));
    }

    static Map<String, String> describe() {
        return Map.of("algorithm", "sliding-window PN-counter CRDT", "store", "Valkey");
    }
}
