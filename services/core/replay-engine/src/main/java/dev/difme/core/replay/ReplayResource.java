package dev.difme.core.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.difme.core.common.events.PaymentEvent;
import dev.difme.core.common.projection.PaymentProjector;
import dev.difme.core.common.projection.PaymentView;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * POST /v1/replay — re-applies a range of events through the same pure projector used live, with event_timestamp
 * as the only clock, and compares the result with the stored read model. Any difference increments
 * difme_replay_divergence_total → P1 alert (PDF §10.4).
 */
@Path("/v1/replay")
@RunOnVirtualThread
public class ReplayResource {
    public record ReplayRequest(String topic, int partition, long fromOffset, long toOffset) {}
    public record ReplayReport(long eventsReplayed, int paymentsChecked, List<String> divergent) {}

    @Inject ObjectMapper json;
    @Inject AgroalDataSource db;
    @Inject MeterRegistry metrics;
    @ConfigProperty(name = "difme.kafka.bootstrap") String bootstrap;

    private static final Map<String, Class<? extends PaymentEvent>> TYPES = Map.of(
            "dev.difme.payments.PaymentInitiated.v1", PaymentEvent.PaymentInitiated.class,
            "dev.difme.payments.PaymentCompleted.v1", PaymentEvent.PaymentCompleted.class,
            "dev.difme.payments.PaymentCompensated.v1", PaymentEvent.PaymentCompensated.class,
            "dev.difme.payments.PaymentRejected.v1", PaymentEvent.PaymentRejected.class);

    @POST
    public ReplayReport replay(ReplayRequest req) throws Exception {
        Map<String, PaymentView> rebuilt = new HashMap<>();
        long count = 0;
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            TopicPartition tp = new TopicPartition(req.topic(), req.partition());
            c.assign(List.of(tp));
            c.seek(tp, req.fromOffset());
            while (c.position(tp) <= req.toOffset()) {
                var batch = c.poll(Duration.ofMillis(500));
                if (batch.isEmpty()) break;
                for (ConsumerRecord<String, String> r : batch) {
                    if (r.offset() > req.toOffset()) break;
                    var typeHeader = r.headers().lastHeader("ce_type");
                    if (typeHeader == null) continue;
                    Class<? extends PaymentEvent> type = TYPES.get(new String(typeHeader.value(), StandardCharsets.UTF_8));
                    if (type == null) continue;
                    PaymentEvent e = json.readValue(r.value(), type);
                    rebuilt.merge(e.paymentId().toString(), PaymentProjector.apply(null, e),
                            (old, ignored) -> PaymentProjector.apply(old, e));
                    count++;
                }
            }
        }
        List<String> divergent = new ArrayList<>();
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT status, version, updated_at FROM read.payment_view WHERE payment_id = ?::uuid")) {
            for (PaymentView v : rebuilt.values()) {
                ps.setString(1, v.paymentId);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean same = rs.next() && rs.getString(1).equals(v.status) && rs.getLong(2) == v.version
                            && rs.getString(3).equals(v.updatedAt);
                    if (!same) divergent.add(v.paymentId);
                }
            }
        }
        metrics.counter("difme_replay_divergence_total").increment(divergent.size());
        return new ReplayReport(count, rebuilt.size(), divergent);
    }
}
