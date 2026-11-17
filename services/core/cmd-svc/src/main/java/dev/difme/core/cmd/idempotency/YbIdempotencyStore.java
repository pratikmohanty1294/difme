package dev.difme.core.cmd.idempotency;

import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.ConsistencyProfile;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * PDF §9 command-layer dedup on YugabyteDB (ADR-015 replaces FoundationDB): a serializable check-and-set on a
 * unique-key table. Same contract as specs/tla/Idempotency.tla: a key is applied at most once per TTL window and
 * duplicates get the original payment id back.
 */
@ApplicationScoped
public class YbIdempotencyStore implements IdempotencyStore {
    private final AgroalDataSource db;
    private final long ttlMs;

    public YbIdempotencyStore(AgroalDataSource db, @ConfigProperty(name = "difme.idempotency.ttl") Duration ttl) {
        this.db = db;
        this.ttlMs = ttl.toMillis();
    }

    @Override
    @ConsistencyProfile(Consistency.LINEARIZABLE)
    public Optional<UUID> claim(UUID key, UUID paymentId, long nowEpochMs) {
        Timestamp now = Timestamp.from(Instant.ofEpochMilli(nowEpochMs));
        Timestamp expiredBefore = Timestamp.from(Instant.ofEpochMilli(nowEpochMs - ttlMs));
        try (Connection c = db.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try {
                // New key, or an expired one: take it. A live key leaves the row untouched and returns nothing.
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO core.idempotency_key (idempotency_key, payment_id, stored_at) VALUES (?, ?, ?)
                        ON CONFLICT (idempotency_key) DO UPDATE SET payment_id = EXCLUDED.payment_id, stored_at = EXCLUDED.stored_at
                          WHERE core.idempotency_key.stored_at < ?
                        RETURNING payment_id""")) {
                    ps.setObject(1, key);
                    ps.setObject(2, paymentId);
                    ps.setTimestamp(3, now);
                    ps.setTimestamp(4, expiredBefore);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { c.commit(); return Optional.empty(); }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT payment_id FROM core.idempotency_key WHERE idempotency_key = ?")) {
                    ps.setObject(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        Optional<UUID> original = rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
                        c.commit();
                        return original;
                    }
                }
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("idempotency check failed", e);
        }
    }
}
