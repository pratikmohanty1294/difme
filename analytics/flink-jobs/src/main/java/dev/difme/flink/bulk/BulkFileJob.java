package dev.difme.flink.bulk;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.util.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;

/**
 * ADR-011 bulk file pipeline (BATCH mode):
 *   file splits → parse (CSV / fixed-width / JSON lines) → schema checks → in-file duplicate check (keyed)
 *   → async batched linked-record validation (static cached + live) → embedded DMN limits
 *   → valid records (Kafka / file / discard) + rejected records (error file) + run report.
 * Excel files are converted first by {@link ExcelToCsv}.
 */
public final class BulkFileJob {
    public record Options(String input, String format, List<String> columns, String layout, boolean header,
                          String dmnXml, int batch, int parallelism, String outDir, String validSink,
                          String kafkaBootstrap, String validTopic) {}

    private BulkFileJob() {}

    public static void main(String[] args) throws Exception {
        ParameterTool p = ParameterTool.fromArgs(args);
        String dmnPath = p.get("dmn-file", "/opt/flink/usrlib/payment-limits.dmn");
        Options o = new Options(
                p.getRequired("input"), p.get("format", "csv"),
                Arrays.asList(p.get("columns", String.join(",", PaymentFileRules.COLUMNS)).split(",")),
                p.get("layout", ""), p.getBoolean("header", true),
                Files.readString(java.nio.file.Path.of(dmnPath)), p.getInt("batch", 1000), p.getInt("parallelism", 4),
                p.getRequired("out-dir"), p.get("valid-sink", "kafka"),
                p.get("kafka-bootstrap", "difme-kafka-bootstrap.streaming.svc:9092"), p.get("valid-topic", "bulk.validated.v1"));
        ReferenceStore store = new JdbcReferenceStore(p.getRequired("jdbc-url"), p.get("jdbc-user", "difme"), p.get("jdbc-password", ""));

        Configuration conf = new Configuration();
        if (p.getBoolean("local", false)) {   // benchmark / CI: size the embedded MiniCluster
            conf.set(TaskManagerOptions.MANAGED_MEMORY_SIZE, MemorySize.parse(p.get("managed-memory", "1g")));
            conf.set(TaskManagerOptions.NUM_TASK_SLOTS, o.parallelism());
        }
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        long t0 = System.nanoTime();
        build(env, o, store);
        env.execute("bulk-file " + o.input());
        double seconds = (System.nanoTime() - t0) / 1e9;
        System.out.printf("{\"input\":\"%s\",\"seconds\":%.1f}%n", o.input(), seconds);
    }

    public static void build(StreamExecutionEnvironment env, Options o, ReferenceStore store) throws Exception {
        env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        env.setParallelism(o.parallelism());
        String fileId = sha256(o.input()).substring(0, 16);
        RecordParser parser = switch (o.format()) {
            case "fixed" -> RecordParser.fixedWidth(o.layout());
            case "json" -> RecordParser.json(o.columns());
            default -> RecordParser.csv(o.columns(), ',');
        };
        boolean header = o.header() && !"json".equals(o.format());

        FileSource<BulkRecord> source = FileSource.forRecordStreamFormat(new OffsetLineFormat(fileId, header), new Path(o.input())).build();
        DataStream<BulkRecord> parsed = env.fromSource(source, WatermarkStrategy.noWatermarks(), "file")
                .map(r -> { parser.parse(r); if (r.valid()) PaymentFileRules.schema(r); return r; })
                .returns(BulkRecord.class).name("parse+schema");

        DataStream<BulkRecord> deduped = parsed
                .keyBy(r -> r.valid() ? r.get(PaymentFileRules.REF) : r.id, Types.STRING)
                .process(new InFileDuplicates()).returns(BulkRecord.class).name("in-file-duplicates");

        DataStream<BulkRecord> validated = AsyncDataStream.unorderedWait(deduped,
                new LinkedValidation(store, new DmnRules("payment-limits", o.dmnXml()), o.batch(), "accounts@" + fileId),
                120, TimeUnit.SECONDS, 20_000).returns(BulkRecord.class).name("linked+dmn");

        List<String> columns = parser.columns();
        DataStream<String> valid = validated.filter(BulkRecord::valid).map(r -> toJson(r, columns)).returns(String.class);
        DataStream<String> rejected = validated.filter(r -> !r.valid())
                .map(r -> r.offset + "," + csv(r.errors) + "," + csv(r.line())).returns(String.class);

        switch (o.validSink()) {
            case "kafka" -> valid.sinkTo(KafkaSink.<String>builder()
                    .setBootstrapServers(o.kafkaBootstrap())
                    .setRecordSerializer(KafkaRecordSerializationSchema.builder().setTopic(o.validTopic())
                            .setValueSerializationSchema(new SimpleStringSchema()).build())
                    // Batch mode: at-least-once flush at end; consumers dedupe on the record id (ADR-011 idempotency).
                    .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE).build()).name("valid→kafka");
            case "file" -> valid.sinkTo(FileSink.forRowFormat(new Path(o.outDir() + "/valid"), new SimpleStringEncoder<String>()).build()).name("valid→file");
            default -> valid.sinkTo(new DiscardingSink<>()).name("valid→discard");
        }
        rejected.sinkTo(FileSink.forRowFormat(new Path(o.outDir() + "/errors"), new SimpleStringEncoder<String>()).build()).name("errors→file");

        // Counts per partition first (no shuffle of 10M records), then one tiny global sum.
        validated.map(r -> Tuple2.of(r.valid() ? 1L : 0L, r.valid() ? 0L : 1L)).returns(Types.TUPLE(Types.LONG, Types.LONG))
                .fullWindowPartition()
                .reduce((a, b) -> Tuple2.of(a.f0 + b.f0, a.f1 + b.f1))
                .keyBy(t -> 0, Types.INT)
                .reduce((a, b) -> Tuple2.of(a.f0 + b.f0, a.f1 + b.f1))
                .map(t -> "{\"fileId\":\"" + fileId + "\",\"valid\":" + t.f0 + ",\"rejected\":" + t.f1 + "}").returns(String.class)
                .setParallelism(1)
                .sinkTo(FileSink.forRowFormat(new Path(o.outDir() + "/report"), new SimpleStringEncoder<String>()).build())
                .setParallelism(1).name("report");
    }

    static String toJson(BulkRecord r, List<String> columns) {
        StringBuilder b = new StringBuilder(160).append("{\"recordId\":\"").append(r.id).append('"');
        for (int i = 0; i < columns.size(); i++) {
            String v = r.get(i);
            if (v == null) continue;
            b.append(",\"").append(columns.get(i)).append("\":\"").append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return b.append('}').toString();
    }

    static String csv(String s) {
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** Content hash of the input file — the stable file identity behind record ids. */
    static String sha256(String path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Path p = new Path(path);
        try (InputStream in = FileSystem.get(p.toUri()).open(p)) {
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
