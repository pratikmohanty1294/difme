package dev.difme.flink.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.functions.PatternProcessFunction;
import org.apache.flink.cep.pattern.Pattern;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * PDF §7.4 L3 — Flink CEP fraud patterns on payments.events.v1 (CloudEvents, binary mode):
 *   velocity: ≥ 5 payments from one debit account within 60 s → HARD_BLOCK alert
 *   amount:   single payment above the large-amount threshold → REVIEW_QUEUE alert
 * Alerts go to fraud.alerts.v1 (api/avro/FraudAlert.avsc shape, JSON-encoded).
 */
public final class FraudCepJob {
    public record Payment(String paymentId, String debitAccountId, long amountMinor, long eventTimeMs) implements Serializable {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private FraudCepJob() {}

    public static void main(String[] args) throws Exception {
        ParameterTool p = ParameterTool.fromArgs(args);
        String bootstrap = p.get("kafka-bootstrap", "difme-kafka-bootstrap.streaming.svc:9092");
        long largeAmount = p.getLong("large-amount-minor", 50_000_000L);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(10_000);

        KafkaSource<Payment> source = KafkaSource.<Payment>builder()
                .setBootstrapServers(bootstrap).setTopics("payments.events.v1").setGroupId("fraud-cep")
                .setStartingOffsets(OffsetsInitializer.earliest())   // resumes from checkpointed offsets after the first start
                .setDeserializer(new PaymentDeserializer()).build();

        DataStream<Payment> payments = env.fromSource(source,
                WatermarkStrategy.<Payment>forBoundedOutOfOrderness(Duration.ofSeconds(5)).withTimestampAssigner((e, ts) -> e.eventTimeMs()),
                "payments");

        Pattern<Payment, ?> velocity = Pattern.<Payment>begin("burst").where(SimpleCondition.of(x -> true))
                .times(5).within(Duration.ofSeconds(60));

        DataStream<String> velocityAlerts = CEP.pattern(payments.keyBy(Payment::debitAccountId), velocity)
                .process(new PatternProcessFunction<Payment, String>() {
                    @Override
                    public void processMatch(Map<String, List<Payment>> match, Context ctx, Collector<String> out) {
                        List<Payment> burst = match.get("burst");
                        Payment last = burst.get(burst.size() - 1);
                        out.collect(alert(last.paymentId(), "L3_CEP", "HARD_BLOCK", 1.0, "velocity:5-in-60s:" + last.debitAccountId()));
                    }
                });

        DataStream<String> amountAlerts = payments.filter(x -> x.amountMinor() > largeAmount)
                .map(x -> alert(x.paymentId(), "L3_CEP", "REVIEW_QUEUE", 0.7, "amount>" + largeAmount));

        velocityAlerts.union(amountAlerts).sinkTo(KafkaSink.<String>builder().setBootstrapServers(bootstrap)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder().setTopic("fraud.alerts.v1")
                        .setValueSerializationSchema(new SimpleStringSchema()).build())
                .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE).setTransactionalIdPrefix("fraud-cep").build());
        env.execute("fraud-cep");
    }

    static String alert(String paymentId, String layer, String action, double score, String rule) {
        return "{\"paymentId\":\"" + paymentId + "\",\"layer\":\"" + layer + "\",\"action\":\"" + action
                + "\",\"score\":" + score + ",\"rule\":\"" + rule + "\"}";
    }

    /** Keeps only PaymentInitiated CloudEvents; reads the domain fields from the JSON payload. */
    static final class PaymentDeserializer implements KafkaRecordDeserializationSchema<Payment> {
        @Override
        public void deserialize(ConsumerRecord<byte[], byte[]> r, Collector<Payment> out) throws java.io.IOException {
            var type = r.headers().lastHeader("ce_type");
            if (type == null || !new String(type.value(), StandardCharsets.UTF_8).endsWith("PaymentInitiated.v1")) return;
            JsonNode n = JSON.readTree(r.value());
            out.collect(new Payment(n.path("paymentId").asText(), n.path("debitAccountId").asText(),
                    n.path("amount").path("amountMinor").asLong(), Instant.parse(n.path("occurredAt").asText()).toEpochMilli()));
        }

        @Override
        public TypeInformation<Payment> getProducedType() {
            return TypeInformation.of(Payment.class);
        }
    }
}
