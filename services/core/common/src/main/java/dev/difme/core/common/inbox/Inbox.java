package dev.difme.core.common.inbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Consumer-side idempotency (PDF §9 "Inbox table per service", TTL 7 days).
 * Must be called inside the same local transaction as the projection write, on the same connection.
 */
public final class Inbox {
    private Inbox() {}

    /** @return true if the event is new (and is now recorded), false if it was already processed. */
    public static boolean claim(Connection c, String consumer, UUID eventId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO inbox.processed_event (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, consumer);
            ps.setObject(2, eventId);
            return ps.executeUpdate() == 1;
        }
    }
}
