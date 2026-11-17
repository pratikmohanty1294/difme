package dev.difme.core.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.difme.core.common.events.PaymentEvent;
import dev.difme.core.common.inbox.Inbox;
import dev.difme.core.common.projection.PaymentProjector;
import dev.difme.core.common.projection.PaymentView;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.redis.datasource.RedisDataSource;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

/**
 * PDF §7.1 steps 9–11: consume CDC CloudEvent → inbox check (skip duplicates) → update read model.
 * Inbox claim and projection write share one local transaction (specs/tla/InboxOutbox.tla).
 * After commit: refresh L2 and broadcast L1 invalidation.
 */
@ApplicationScoped
public class PaymentProjectionConsumer {
    static final String CONSUMER = "projection-svc";

    @Inject AgroalDataSource db;
    @Inject ObjectMapper json;
    @Inject RedisDataSource redis;
    @Inject MeterRegistry metrics;

    @Incoming("payments")
    @RunOnVirtualThread
    public CompletionStage<Void> on(Message<String> msg) {
        try {
            var meta = msg.getMetadata(IncomingKafkaRecordMetadata.class).orElseThrow();
            UUID eventId = UUID.fromString(header(meta, "ce_id"));
            PaymentEvent event = EventCodec.decode(json, header(meta, "ce_type"), msg.getPayload());
            PaymentView updated = project(eventId, event);
            if (updated != null) {
                String key = "payment:" + updated.paymentId;
                redis.value(String.class).set(key, json.writeValueAsString(updated));
                redis.pubsub(String.class).publish("difme:l1-invalidate", key);
                metrics.counter("difme_projection_applied_total").increment();
            } else {
                metrics.counter("difme_projection_duplicates_total").increment();
            }
            return msg.ack();
        } catch (Exception e) {
            return msg.nack(e);
        }
    }

    private PaymentView project(UUID eventId, PaymentEvent event) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!Inbox.claim(c, CONSUMER, eventId)) { c.rollback(); return null; }
                PaymentView next = PaymentProjector.apply(load(c, event.paymentId()), event);
                upsert(c, next);
                c.commit();
                return next;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private PaymentView load(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT payment_id::text, status, amount_minor, currency, channel, workflow_id, debit_account_id::text, "
                        + "credit_account_id::text, version, updated_at FROM read.payment_view WHERE payment_id = ? FOR UPDATE")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                PaymentView v = new PaymentView();
                v.paymentId = rs.getString(1); v.status = rs.getString(2); v.amountMinor = rs.getLong(3);
                v.currency = rs.getString(4); v.channel = rs.getString(5); v.workflowId = rs.getString(6);
                v.debitAccountId = rs.getString(7); v.creditAccountId = rs.getString(8); v.version = rs.getLong(9);
                v.updatedAt = rs.getString(10);
                return v;
            }
        }
    }

    private void upsert(Connection c, PaymentView v) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO read.payment_view (payment_id, status, amount_minor, currency, channel, workflow_id,
                                               debit_account_id, credit_account_id, version, updated_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?::uuid, ?::uuid, ?, ?)
                ON CONFLICT (payment_id) DO UPDATE SET status = EXCLUDED.status, workflow_id = EXCLUDED.workflow_id,
                    version = EXCLUDED.version, updated_at = EXCLUDED.updated_at
                """)) {
            ps.setString(1, v.paymentId); ps.setString(2, v.status); ps.setLong(3, v.amountMinor);
            ps.setString(4, v.currency); ps.setString(5, v.channel); ps.setString(6, v.workflowId);
            ps.setString(7, v.debitAccountId); ps.setString(8, v.creditAccountId); ps.setLong(9, v.version);
            ps.setString(10, v.updatedAt);
            ps.executeUpdate();
        }
    }

    private static String header(IncomingKafkaRecordMetadata<?, ?> meta, String name) {
        Header h = meta.getHeaders().lastHeader(name);
        if (h == null) throw new IllegalArgumentException("missing header " + name);
        return new String(h.value(), StandardCharsets.UTF_8);
    }
}
