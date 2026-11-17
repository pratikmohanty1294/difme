-- PDF §5.5 / §5.9 — streaming SQL view behind the live settlements dashboard (RisingWave, ADR-001).
-- Source: CloudEvents (structured JSON) produced by the settlements-cdc Debezium connector.
CREATE SOURCE IF NOT EXISTS settlements_cdc (
    id VARCHAR,
    type VARCHAR,
    time VARCHAR,
    data JSONB
) WITH (
    connector = 'kafka',
    topic = 'settlements.cdc.v1',
    properties.bootstrap.server = 'difme-kafka-bootstrap.streaming.svc:9092',
    scan.startup.mode = 'earliest'
) FORMAT PLAIN ENCODE JSON;

-- Latest state per reservation (top-1 per key), then aggregate per bank pair.
CREATE MATERIALIZED VIEW IF NOT EXISTS reservation_latest AS
SELECT reservation_id, bank_pair, amount_minor, status, updated_at FROM (
    SELECT (data -> 'after' ->> 'reservation_id')         AS reservation_id,
           (data -> 'after' ->> 'bank_pair')              AS bank_pair,
           (data -> 'after' ->> 'amount_minor')::BIGINT   AS amount_minor,
           (data -> 'after' ->> 'status')                 AS status,
           (data -> 'after' ->> 'updated_at')::TIMESTAMPTZ AS updated_at,
           ROW_NUMBER() OVER (PARTITION BY (data -> 'after' ->> 'reservation_id') ORDER BY time DESC) AS rn
      FROM settlements_cdc
     WHERE data -> 'after' IS NOT NULL
) WHERE rn = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS settlement_positions AS
SELECT bank_pair,
       'INR'                                                                   AS currency,
       SUM(CASE WHEN status = 'RESERVED' THEN amount_minor ELSE 0 END)          AS reserved_minor,
       SUM(CASE WHEN status = 'RELEASED' THEN amount_minor ELSE 0 END)          AS released_minor,
       COUNT(*) FILTER (WHERE status = 'RESERVED')                              AS open_count,
       MIN(updated_at) FILTER (WHERE status = 'RESERVED')                       AS oldest_open_at
  FROM reservation_latest
 GROUP BY bank_pair;
