package dev.difme.core.rehydration;

import com.google.protobuf.ByteString;
import dev.difme.grpc.payments.v1.RehydrateRequest;
import dev.difme.grpc.payments.v1.RehydrateResponse;
import dev.difme.grpc.payments.v1.RehydrationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.grpc.GrpcService;
import io.quarkus.redis.datasource.RedisDataSource;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * PDF §7.3: device reconnects → uploads only changed CRDT entries (vector-clock delta) → service looks up the
 * client's last committed offset → replays missed CDC events from that offset → snapshot_complete. Deltas are
 * forwarded to crdt.deltas.v1 where merge is commutative/idempotent, so at-least-once is safe (RPO = 0).
 */
@GrpcService
public class RehydrationGrpcService implements RehydrationService {
    @Inject RedisDataSource redis;
    @Inject MeterRegistry metrics;
    @ConfigProperty(name = "difme.kafka.bootstrap") String bootstrap;
    @ConfigProperty(name = "difme.rehydration.topic") String topic;
    @ConfigProperty(name = "difme.rehydration.deltas-topic") String deltasTopic;

    @Override
    public Multi<RehydrateResponse> rehydrate(Multi<RehydrateRequest> requests) {
        return requests.emitOn(Infrastructure.getDefaultWorkerPool())
                .onItem().transformToMultiAndConcatenate(req -> Multi.createFrom().iterable(handle(req)));
    }

    private List<RehydrateResponse> handle(RehydrateRequest req) {
        Timer.Sample t = Timer.start(metrics);
        forwardDelta(req);
        String offsetKey = "rehydrate:offset:" + req.getClientId();
        String stored = redis.value(String.class).get(offsetKey);
        long from = Math.max(req.getLastCommittedOffset(), stored == null ? 0 : Long.parseLong(stored));
        List<RehydrateResponse> out = replay(from);
        long last = out.isEmpty() ? from : out.get(out.size() - 1).getOffset() + 1;
        redis.value(String.class).set(offsetKey, Long.toString(last));
        out.add(RehydrateResponse.newBuilder().setOffset(last).setSnapshotComplete(true).build());
        t.stop(Timer.builder("difme_rehydration_seconds").publishPercentileHistogram().register(metrics));
        return out;
    }

    private void forwardDelta(RehydrateRequest req) {
        if (req.getAutomergeChanges().isEmpty()) return;
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("enable.idempotence", "true");
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(p, new ByteArraySerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(deltasTopic, req.getClientId().getBytes(StandardCharsets.UTF_8),
                    req.toByteArray()));
        }
    }

    /** Single-partition replay for clarity; production assigns the client's partition from its account key. */
    private List<RehydrateResponse> replay(long fromOffset) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("enable.auto.commit", "false");
        List<RehydrateResponse> out = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(p, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            TopicPartition tp = new TopicPartition(topic, 0);
            consumer.assign(List.of(tp));
            long end = consumer.endOffsets(List.of(tp)).get(tp);
            consumer.seek(tp, fromOffset);
            while (consumer.position(tp) < end) {
                for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(200))) {
                    Header id = r.headers().lastHeader("ce_id");
                    out.add(RehydrateResponse.newBuilder()
                            .setEventId(id == null ? "" : new String(id.value(), StandardCharsets.UTF_8))
                            .setOffset(r.offset())
                            .setCloudevent(ByteString.copyFrom(r.value() == null ? new byte[0] : r.value()))
                            .build());
                }
            }
        }
        return out;
    }
}
