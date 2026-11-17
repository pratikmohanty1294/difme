package dev.difme.core.cmd.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Outbox row — same transaction as the aggregate; Debezium's outbox router publishes it as a CloudEvent. */
@Entity
@Table(name = "outbox", schema = "core")
public class OutboxEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "aggregate_type", nullable = false) public String aggregateType;
    @Column(name = "aggregate_id", nullable = false) public UUID aggregateId;
    @Column(nullable = false) public String type;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") public String payload;
    @Column(name = "trace_id") public String traceId;
    @Column(name = "event_timestamp", nullable = false) public Instant eventTimestamp;
}
