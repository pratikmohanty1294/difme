# Formal verification (PDF §3.2, §11.2)

TLA+ specifications model-checked by TLC as a **hard CI gate**: any safety or liveness violation blocks merge.

| Spec | Verifies |
|---|---|
| `PaymentSaga.tla` | Saga steps + compensation: money is conserved; every started saga ends COMPLETED or fully COMPENSATED |
| `Ledger.tla` | Double-entry invariant: sum of all balances is always 0; no overdraft on debit-limited accounts |
| `InboxOutbox.tla` | Outbox written atomically with state; at-least-once delivery + inbox dedup ⇒ exactly-once effect |
| `CrdtMerge.tla` | Offline edge spending as an escrow CRDT: total spend never exceeds the limit even while partitioned; views never run ahead of the owner; merge is commutative, associative and idempotent; replicas converge once operations stop (5,268 states, ~5 s) |
| `Idempotency.tla` | UUIDv7 dedup: a key is applied at most once within its TTL, duplicates get the original result |

Run: `make tla` (calls `check.sh`).
