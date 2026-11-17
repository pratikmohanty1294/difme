package dev.difme.core.cmd.idempotency;

import com.apple.foundationdb.Database;
import com.apple.foundationdb.FDB;
import com.apple.foundationdb.tuple.Tuple;
import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.ConsistencyProfile;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** FoundationDB strictly-serializable check-and-set (PDF §8 LINEARIZABLE). */
@ApplicationScoped
public class FdbIdempotencyStore implements IdempotencyStore {
    private static final int API_VERSION = 730;
    private final Database db;
    private final long ttlMs;

    public FdbIdempotencyStore(@ConfigProperty(name = "difme.fdb.cluster-file") String clusterFile,
                               @ConfigProperty(name = "difme.idempotency.ttl") Duration ttl) {
        this.db = FDB.selectAPIVersion(API_VERSION).open(clusterFile);
        this.ttlMs = ttl.toMillis();
    }

    @Override
    @ConsistencyProfile(Consistency.LINEARIZABLE)
    public Optional<UUID> claim(UUID key, UUID paymentId, long nowEpochMs) {
        byte[] k = Tuple.from("idem", key).pack();
        return db.run(tr -> {
            byte[] existing = tr.get(k).join();
            if (existing != null) {
                Tuple t = Tuple.fromBytes(existing);
                long storedAt = t.getLong(1);
                if (nowEpochMs - storedAt < ttlMs) return Optional.of(t.getUUID(0));
            }
            tr.set(k, Tuple.from(paymentId, nowEpochMs).pack());
            return Optional.<UUID>empty();
        });
    }

    void close(@Observes ShutdownEvent ev) {
        db.close();
    }
}
