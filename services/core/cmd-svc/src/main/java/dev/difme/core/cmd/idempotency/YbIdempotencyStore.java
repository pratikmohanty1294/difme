package dev.difme.core.cmd.idempotency;

import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.ConsistencyProfile;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * PDF §9 command-layer dedup on YugabyteDB (ADR-015): a serializable check-and-set on a unique-key table, same contract
 * as specs/tla/Idempotency.tla. {@link #claim} does not manage its own transaction (ADR-017): the connection is
 * enlisted in the caller's JTA transaction, so the claim, the payment and its outbox event commit atomically.
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
        try (Connection c = db.getConnection()) {
            // New key, or an expired one: take it. A live key leaves the row untouched and returns nothing.
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO core.idempotency_key (idempotency_key, payment_id, stored_at) VALUES (?, ?, ?)
                    ON CONFLICT (idempotency_key) DO UPDATE SET payment_id = EXCLUDED.payment_id, stored_at = EXCLUDED.stored_at
                      WHERE core.idempotency_key.stored_at < ?
                    RETURNING payment_id""")) {
                ps.setObject(1, key);
                ps.setObject(2, paymentId);
                ps.setTimestamp(3, at(nowEpochMs));
                ps.setTimestamp(4, at(nowEpochMs - ttlMs));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.empty();
                }
            }
            return lookup(c, key, Long.MIN_VALUE);
        } catch (SQLException e) {
            throw new IllegalStateException("idempotency claim failed", e);
        }
    }

    @Override
    public Optional<UUID> find(UUID key, long nowEpochMs) {
        try (Connection c = db.getConnection()) {
            return lookup(c, key, nowEpochMs - ttlMs);
        } catch (SQLException e) {
            throw new IllegalStateException("idempotency lookup failed", e);
        }
    }

    private static Optional<UUID> lookup(Connection c, UUID key, long notBeforeMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT payment_id FROM core.idempotency_key WHERE idempotency_key = ? AND stored_at >= ?")) {
            ps.setObject(1, key);
            ps.setTimestamp(2, notBeforeMs == Long.MIN_VALUE ? new Timestamp(0) : at(notBeforeMs));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        }
    }

    private static Timestamp at(long epochMs) {
        return Timestamp.from(Instant.ofEpochMilli(epochMs));
    }
}
