-- ADR-015: idempotency keys move from FoundationDB to YugabyteDB (PDF §9; same TLA+ contract).
CREATE TABLE IF NOT EXISTS core.idempotency_key (
    idempotency_key uuid PRIMARY KEY,          -- UUIDv7
    payment_id      uuid NOT NULL,
    stored_at       timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idempotency_key_ttl ON core.idempotency_key (stored_at);   -- 24h purge job
