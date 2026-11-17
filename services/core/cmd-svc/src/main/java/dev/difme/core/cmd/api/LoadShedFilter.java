package dev.difme.core.cmd.api;

import dev.difme.core.common.resilience.AdaptiveLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Throttling manager for the payment API (ADR-017): an adaptive concurrency limit sheds load early with
 * 503 + Retry-After instead of letting requests queue until everything times out. Health and metrics endpoints are
 * never shed. The gateway's fixed per-route rate limits sit in front of this; this guards the service itself.
 */
@Provider
public class LoadShedFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final String STARTED = "difme.loadshed.started";

    @Inject MeterRegistry metrics;
    @ConfigProperty(name = "difme.loadshed.initial") int initial;
    @ConfigProperty(name = "difme.loadshed.min") int min;
    @ConfigProperty(name = "difme.loadshed.max") int max;
    @ConfigProperty(name = "difme.loadshed.target-latency") Duration target;
    private AdaptiveLimiter limiter;

    @PostConstruct
    void init() {
        limiter = new AdaptiveLimiter(initial, min, max, target);
        metrics.gauge("difme_loadshed_limit", limiter, AdaptiveLimiter::limit);
        metrics.gauge("difme_loadshed_inflight", limiter, AdaptiveLimiter::inflight);
        metrics.more().counter("difme_loadshed_rejected_total", java.util.List.of(), limiter, AdaptiveLimiter::rejected);
    }

    @Override
    public void filter(ContainerRequestContext req) {
        if (!req.getUriInfo().getPath().startsWith("/v1")) return;
        if (!limiter.tryAcquire()) {
            req.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "1")
                    .type("application/problem+json")
                    .entity("{\"type\":\"about:blank\",\"title\":\"Overloaded\",\"status\":503,"
                            + "\"detail\":\"Too many requests in flight; retry with the same Idempotency-Key.\"}")
                    .build());
            return;
        }
        req.setProperty(STARTED, System.nanoTime());
    }

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        Object started = req.getProperty(STARTED);
        if (started == null) return;                 // not limited, or shed before acquiring
        limiter.release(System.nanoTime() - (long) started, res.getStatus() >= 500);
    }

}
