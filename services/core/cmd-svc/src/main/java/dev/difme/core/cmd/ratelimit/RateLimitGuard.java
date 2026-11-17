package dev.difme.core.cmd.ratelimit;

import dev.difme.core.common.resilience.TokenBucket;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Shared rate limit (PN-counter on Valkey) behind timeout, bulkhead and circuit breaker (ADR-017).
 * Not retried: an acquire is not idempotent, so a retry could count one request twice.
 * Any failure (timeout, open circuit, full bulkhead, error) falls back to a conservative per-pod token bucket, so an
 * outage of the shared limiter degrades throughput instead of stopping payments or removing the limit.
 */
@ApplicationScoped
public class RateLimitGuard {
    @Inject @RestClient RateLimiterClient client;
    @Inject MeterRegistry metrics;
    private final TokenBucket degraded;

    public RateLimitGuard(@ConfigProperty(name = "difme.ratelimit.degraded-per-pod-rps", defaultValue = "50") double rps,
                          @ConfigProperty(name = "difme.ratelimit.degraded-burst", defaultValue = "20") int burst) {
        this.degraded = new TokenBucket(rps, burst);
    }

    @Timeout(100)
    @Bulkhead(64)
    @CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5, delay = 5000, successThreshold = 3)
    @Fallback(fallbackMethod = "degradedAllow")
    public boolean allow(String subject, String scope, long eventTimeMs) {
        return client.acquire(new RateLimiterClient.Acquire(subject, scope, eventTimeMs)).allowed();
    }

    boolean degradedAllow(String subject, String scope, long eventTimeMs) {
        boolean ok = degraded.tryAcquire();
        metrics.counter("difme_ratelimit_degraded_total", "allowed", String.valueOf(ok)).increment();
        return ok;
    }
}
