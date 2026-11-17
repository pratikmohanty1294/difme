-- PDF §5.4 / §8 / §9. UUIDv7 primary keys everywhere.
CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS read;
CREATE SCHEMA IF NOT EXISTS inbox;
CREATE SCHEMA IF NOT EXISTS settlement;

CREATE TABLE core.payment (
    payment_id        uuid PRIMARY KEY,
    idempotency_key   uuid NOT NULL UNIQUE,
    debit_account_id  uuid NOT NULL,
    credit_account_id uuid NOT NULL,
    amount_minor      bigint NOT NULL CHECK (amount_minor > 0),
    currency          char(3) NOT NULL,
    channel           varchar(16) NOT NULL,
    target_bank       varchar(64),
    status            varchar(24) NOT NULL,
    workflow_id       varchar(64),
    ledger_transfer_id varchar(40),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint NOT NULL DEFAULT 0          -- Hibernate @Version optimistic locking
);

-- Outbox: written in the same ACID transaction as core.payment; Debezium reads it via CDC (no dual write).
CREATE TABLE core.outbox (
    id              uuid PRIMARY KEY,                    -- CloudEvents id (UUIDv7)
    aggregate_type  varchar(32) NOT NULL,                -- routes to <aggregate_type>.events.v1
    aggregate_id    uuid NOT NULL,
    type            varchar(96) NOT NULL,
    payload         jsonb NOT NULL,
    trace_id        varchar(64),
    event_timestamp timestamptz NOT NULL                 -- "official now" for deterministic replay
);

CREATE TABLE read.payment_view (
    payment_id        uuid PRIMARY KEY,
    status            varchar(24) NOT NULL,
    amount_minor      bigint NOT NULL,
    currency          char(3) NOT NULL,
    channel           varchar(16),
    workflow_id       varchar(64),
    debit_account_id  uuid,
    credit_account_id uuid,
    version           bigint NOT NULL,
    updated_at        varchar(40) NOT NULL
);

CREATE TABLE read.account_view (
    account_id   uuid PRIMARY KEY,
    holder_name  varchar(140) NOT NULL,
    kyc_status   varchar(16) NOT NULL,
    region       varchar(16) NOT NULL
);

CREATE TABLE inbox.processed_event (
    consumer     varchar(64) NOT NULL,
    event_id     uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
CREATE INDEX processed_event_ttl ON inbox.processed_event (processed_at);   -- 7-day purge job

CREATE TABLE settlement.reservation (
    reservation_id uuid PRIMARY KEY,
    payment_id     uuid NOT NULL UNIQUE,                -- one reservation per saga → idempotent activity
    bank_pair      varchar(64) NOT NULL,
    amount_minor   bigint NOT NULL,
    status         varchar(16) NOT NULL,                -- RESERVED | RELEASED | COMPENSATED
    updated_at     timestamptz NOT NULL
);

CREATE TABLE settlement.instruction (
    instruction_id uuid PRIMARY KEY,
    payment_id     uuid NOT NULL UNIQUE,
    target_bank    varchar(64) NOT NULL,
    status         varchar(16) NOT NULL,
    sent_at        timestamptz NOT NULL
);
