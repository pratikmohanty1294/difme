package dev.difme.core.qry.api;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * Back-office read APIs (PDF §5.9):
 *   settlements → RisingWave streaming materialized view (sub-second refresh, SSE push)
 *   audit       → Iceberg time-travel through Trino
 *   analytics   → StarRocks OLAP
 */
@Path("/v1")
public class BackofficeResource {
    public record Position(String bankPair, String currency, long reservedMinor, long releasedMinor, long open, long lagMs) {}
    public record AuditEntry(String eventId, String type, String at, String actor, String payload) {}
    public record FraudDay(String day, long alerts, long hardBlocks, long falsePositives, double costPerTxnUsd) {}

    private static final Pattern UUID7 = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Inject @DataSource("risingwave") AgroalDataSource risingwave;
    @Inject @DataSource("trino") AgroalDataSource trino;
    @Inject @DataSource("starrocks") AgroalDataSource starrocks;

    @GET @Path("/settlements/positions")
    @RunOnVirtualThread
    public List<Position> positions() throws Exception {
        try (Connection c = risingwave.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT bank_pair, currency, reserved_minor, released_minor, open_count, "
                + "COALESCE((EXTRACT(EPOCH FROM (now() - oldest_open_at)) * 1000)::BIGINT, 0) AS lag_ms "
                + "FROM settlement_positions ORDER BY bank_pair");
             ResultSet rs = ps.executeQuery()) {
            List<Position> out = new ArrayList<>();
            while (rs.next()) out.add(new Position(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)));
            return out;
        }
    }

    @GET @Path("/settlements/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<List<Position>> stream() {
        return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .onItem().transform(t -> {
                    try { return positions(); } catch (Exception e) { throw new IllegalStateException(e); }
                });
    }

    @GET @Path("/audit")
    @RunOnVirtualThread
    public Response audit(@QueryParam("entityId") String entityId, @QueryParam("asOf") String asOf) throws Exception {
        if (entityId == null || !UUID7.matcher(entityId).matches()) return Response.status(422).build();
        String travel = "";
        if (asOf != null && !asOf.isBlank()) travel = " FOR TIMESTAMP AS OF CAST(? AS TIMESTAMP(6) WITH TIME ZONE)";
        String sql = "SELECT event_id, type, CAST(event_timestamp AS VARCHAR), actor, payload FROM iceberg.events.payment_events"
                + travel + " WHERE aggregate_id = ? ORDER BY event_timestamp LIMIT 1000";
        try (Connection c = trino.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (!travel.isEmpty()) ps.setString(i++, Timestamp.from(Instant.parse(asOf)).toInstant().toString());
            ps.setString(i, entityId);
            List<AuditEntry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new AuditEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
            return Response.ok(out).build();
        }
    }

    @GET @Path("/analytics/fraud-daily")
    @RunOnVirtualThread
    public List<FraudDay> fraudDaily(@QueryParam("days") Integer days) throws Exception {
        int n = days == null ? 30 : Math.min(Math.max(days, 1), 365);
        try (Connection c = starrocks.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT CAST(day AS CHAR), alerts, hard_blocks, false_positives, cost_per_txn_usd FROM difme.fraud_daily ORDER BY day DESC LIMIT ?")) {
            ps.setInt(1, n);
            List<FraudDay> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new FraudDay(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getDouble(5)));
            }
            return out.reversed();
        }
    }
}
