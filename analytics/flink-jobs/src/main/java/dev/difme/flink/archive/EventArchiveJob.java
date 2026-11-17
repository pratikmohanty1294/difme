package dev.difme.flink.archive;

import org.apache.flink.util.ParameterTool;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

/**
 * PDF §1.1 / §15 — Day-0 event history: every payment CloudEvent and fraud alert is archived to Iceberg tables in
 * the Polaris REST catalog on SeaweedFS (ADR-005/006). These tables back the compliance time-travel explorer and dbt.
 */
public final class EventArchiveJob {
    private EventArchiveJob() {}

    public static void main(String[] args) throws Exception {
        ParameterTool p = ParameterTool.fromArgs(args);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(p.getLong("checkpoint-ms", 2_000));   // Iceberg commits per checkpoint → E2E < 3 s
        StreamTableEnvironment t = StreamTableEnvironment.create(env);
        String bootstrap = p.get("kafka-bootstrap", "difme-kafka-bootstrap.streaming.svc:9092");

        t.executeSql("""
            CREATE CATALOG lake WITH (
              'type' = 'iceberg', 'catalog-type' = 'rest',
              'uri' = '%s', 'warehouse' = 'difme', 'credential' = '%s', 'scope' = 'PRINCIPAL_ROLE:ALL',
              'io-impl' = 'org.apache.iceberg.aws.s3.S3FileIO',
              's3.endpoint' = '%s', 's3.path-style-access' = 'true')
            """.formatted(p.get("catalog-uri", "http://polaris.lakehouse.svc:8181/api/catalog"),
                System.getenv().getOrDefault("POLARIS_CREDENTIAL", ""),
                p.get("s3-endpoint", "http://seaweedfs-s3.lakehouse.svc:8333")));
        t.executeSql("CREATE DATABASE IF NOT EXISTS lake.events");
        t.executeSql("""
            CREATE TABLE IF NOT EXISTS lake.events.payment_events (
              event_id STRING, aggregate_id STRING, type STRING, event_timestamp TIMESTAMP(3), actor STRING, payload STRING)
            """);
        t.executeSql("""
            CREATE TABLE IF NOT EXISTS lake.events.fraud_alerts (
              payment_id STRING, layer STRING, action STRING, score DOUBLE, rule STRING, archived_at TIMESTAMP(3))
            """);

        t.executeSql("""
            CREATE TEMPORARY TABLE payments_src (
              payload STRING, headers MAP<STRING, BYTES> METADATA FROM 'headers')
            WITH ('connector' = 'kafka', 'topic' = 'payments.events.v1', 'properties.bootstrap.servers' = '%s',
                  'properties.group.id' = 'event-archive', 'scan.startup.mode' = 'group-offsets',
                  'properties.auto.offset.reset' = 'earliest', 'value.format' = 'raw')
            """.formatted(bootstrap));
        t.executeSql("""
            CREATE TEMPORARY TABLE alerts_src (paymentId STRING, layer STRING, action STRING, score DOUBLE, rule STRING)
            WITH ('connector' = 'kafka', 'topic' = 'fraud.alerts.v1', 'properties.bootstrap.servers' = '%s',
                  'properties.group.id' = 'event-archive-alerts', 'scan.startup.mode' = 'group-offsets',
                  'properties.auto.offset.reset' = 'earliest', 'value.format' = 'json')
            """.formatted(bootstrap));

        StatementSet set = t.createStatementSet();
        set.addInsertSql("""
            INSERT INTO lake.events.payment_events
            SELECT CAST(headers['ce_id'] AS STRING), JSON_VALUE(payload, '$.paymentId'), CAST(headers['ce_type'] AS STRING),
                   TO_TIMESTAMP(REGEXP_REPLACE(REPLACE(JSON_VALUE(payload, '$.occurredAt'), 'T', ' '), 'Z$', '')),
                   CAST(headers['actor'] AS STRING), payload
            FROM payments_src
            """);
        set.addInsertSql("""
            INSERT INTO lake.events.fraud_alerts
            SELECT paymentId, layer, action, score, rule, LOCALTIMESTAMP FROM alerts_src
            """);
        set.execute();
    }
}
