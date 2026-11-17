package dev.difme.core.qry.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * PDF §5.4 cache lookup chain: L1 Caffeine (getIfPresent, Window-TinyLFU, &lt;0.5ms) → L2 Valkey (GET, &lt;1ms)
 * → YugabyteDB follower read. Misses fill upward. Coherence: cmd-svc / projection-svc write L2 after commit and
 * publish the key on {@code difme:l1-invalidate}; every instance evicts its L1 copy.
 */
@ApplicationScoped
public class CacheChain {
    public static final String L1_INVALIDATE_CHANNEL = "difme:l1-invalidate";

    private final Cache<String, String> l1;
    private final ValueCommands<String, String> l2;
    private final RedisDataSource redis;
    private final ObjectMapper json;
    private final Duration l2Ttl;
    private final MeterRegistry metrics;

    public CacheChain(RedisDataSource redis, ObjectMapper json, MeterRegistry metrics,
                      @ConfigProperty(name = "difme.cache.l1.max-size") long l1Size,
                      @ConfigProperty(name = "difme.cache.l1.ttl") Duration l1Ttl,
                      @ConfigProperty(name = "difme.cache.l2.ttl") Duration l2Ttl) {
        this.redis = redis;
        this.l2 = redis.value(String.class);
        this.json = json;
        this.l2Ttl = l2Ttl;
        this.metrics = metrics;
        this.l1 = Caffeine.newBuilder().maximumSize(l1Size).expireAfterWrite(l1Ttl).recordStats().build();
    }

    void subscribe(@Observes StartupEvent ev) {
        redis.pubsub(String.class).subscribe(L1_INVALIDATE_CHANNEL, l1::invalidate);
    }

    public <T> Optional<T> get(String key, Class<T> type, Supplier<T> database) {
        String hit = time("l1", () -> l1.getIfPresent(key));
        if (hit != null) return Optional.of(read(hit, type));

        hit = time("l2", () -> l2.get(key));
        if (hit != null) {
            l1.put(key, hit);
            return Optional.of(read(hit, type));
        }

        T fromDb = time("db", database);
        if (fromDb == null) return Optional.empty();
        String serialized = write(fromDb);
        l2.set(key, serialized, new SetArgs().ex(l2Ttl));
        l1.put(key, serialized);
        return Optional.of(fromDb);
    }

    private <R> R time(String tier, Supplier<R> s) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            return s.get();
        } finally {
            sample.stop(Timer.builder("difme_cache_get_seconds").tag("tier", tier).publishPercentileHistogram().register(metrics));
        }
    }

    private <T> T read(String s, Class<T> type) {
        try { return json.readValue(s, type); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
