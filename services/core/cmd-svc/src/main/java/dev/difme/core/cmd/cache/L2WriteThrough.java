package dev.difme.core.cmd.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.difme.core.cmd.persistence.PaymentCommitted;
import dev.difme.core.common.projection.PaymentProjector;
import dev.difme.core.common.projection.PaymentView;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.time.Duration;
import org.jboss.logging.Logger;

/**
 * PDF §5.4 "L2 cache write-through: writes the entity to the L2 cache immediately after DB commit".
 * Runs only on AFTER_SUCCESS, so a rolled-back transaction never reaches the cache. Then publishes an L1
 * invalidation so every qry-svc instance drops its Caffeine copy.
 */
@ApplicationScoped
public class L2WriteThrough {
    public static final String L1_INVALIDATE_CHANNEL = "difme:l1-invalidate";
    private static final Logger LOG = Logger.getLogger(L2WriteThrough.class);

    private final ValueCommands<String, String> values;
    private final RedisDataSource redis;
    @Inject ObjectMapper json;

    public L2WriteThrough(RedisDataSource redis) {
        this.redis = redis;
        this.values = redis.value(String.class);
    }

    void onCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) PaymentCommitted committed) {
        String key = "payment:" + committed.event().paymentId();
        try {
            String cached = values.get(key);
            PaymentView current = cached == null ? null : json.readValue(cached, PaymentView.class);
            PaymentView next = PaymentProjector.apply(current, committed.event());
            values.set(key, json.writeValueAsString(next), new SetArgs().ex(Duration.ofHours(1)));
            redis.pubsub(String.class).publish(L1_INVALIDATE_CHANNEL, key);
        } catch (Exception e) {
            // The cache is an optimisation: a failure here must never fail the committed command.
            redis.key(String.class).del(key);
            LOG.warnf(e, "L2 write-through failed for %s; key evicted", key);
        }
    }
}
