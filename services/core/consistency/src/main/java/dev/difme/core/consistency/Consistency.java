package dev.difme.core.consistency;

/** PDF §14 consistency levels. */
public enum Consistency {
    STRICT,          // TigerBeetle — ledger debit/credit
    SERIALIZABLE,    // YugabyteDB serializable — cross-bank settlement (ADR-001)
    EVENTUAL,        // YugabyteDB follower read — KYC / profile
    CRDT,            // Valkey PN-counter — rate limits
    LINEARIZABLE,    // FoundationDB — idempotency keys, sequence numbers, locks
    APPEND_ONLY,     // Cassandra quorum — audit log
    SNAPSHOT,        // Iceberg — historical analytics
    DURABLE,         // Temporal — workflow state
    BEST_EFFORT,     // Valkey — session / UI state
    ONPREM           // scenario C — PII pinned to on-prem YugabyteDB
}
