package dev.difme.core.cmd.app;

import dev.difme.core.common.resilience.TxRetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Exposes TxRetry's conflict counters. A rising retry rate means serializable contention, which shows up as latency (ADR-017). */
@Startup
@ApplicationScoped
public class TxRetryMetrics {
    TxRetryMetrics(MeterRegistry metrics) {
        metrics.more().counter("difme_tx_retries_total", List.of(), this, m -> TxRetry.retries());
        metrics.more().counter("difme_tx_retries_exhausted_total", List.of(), this, m -> TxRetry.exhausted());
    }
}
