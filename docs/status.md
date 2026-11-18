# Status and next steps

Updated 2026-11-18. Read this first when picking up work, then CONTRIBUTING.md.

## Where things stand
- **Running in the Codespace:** profiles `base` and `core`: Cilium, Argo CD, Kyverno, Envoy Gateway, cert-manager, MetalLB, YugabyteDB, TigerBeetle, Valkey, the shared Postgres (ADR-019), Temporal, flagd and the core services (cmd, qry, saga, rate-limiter, consistency-router).
- **Built and tested, not yet deployed live:** the remaining profiles (streaming, lakehouse, ml, observability, security, process, backoffice, hub).
- **DIFME/DiscountAI (ADR-020):** `services/core/exchange-kernel` (scoring, collateral, slashing, reputation, conservation identity, evidence chains; 8 tests) and `verticals/` (FR-01 live pack, FR-02…09 declared).

## Baseline: payment write path (`ci/measure.sh 200 60s`, latest run)
| Metric | Value |
|---|---|
| Offered / accepted | 200 req/s offered; **13.5 req/s accepted (202)**; 149 req/s shed (503 from the adaptive limiter, limit fell to 8) |
| Latency, accepted path | ~590 ms average (p99 of all requests 664 ms; shed requests return in < 1 ms) |
| Breakdown per accepted payment | DB pool wait ~175 ms · Temporal start 27 ms · rate-limiter 10 ms · Valkey ~18 ms (4.8 calls) · **~360 ms in YugabyteDB statements** |
| DB pool | 4.6 connection checkouts per payment; pool max 20 reached with 8 HTTP requests in flight; 0 serialization retries |
| Node | idle 490m CPU / 7.9 GiB → peak 2.45 cores / 8.2 GiB |
| Largest pods (RAM) | TigerBeetle 2.17 GiB · yb-tserver 467 MiB · cmd-svc 374 MiB (limit 448) · Envoy proxy 312 MiB · temporal-history 315 MiB |

Defects found and fixed while measuring:
- cmd-svc and saga-orchestrator were OOM-killed (JVM memory budget);
- the Redis pool saturated;
- the rate-limiter's native image was missing its Lua script;
- the canary Rollout ran cmd-svc twice;
- the Flyway advisory lock failed on YugabyteDB;
- Temporal had no `default` namespace;
- the cluster went into an eviction loop at 86% disk use.

Details are in docs/knowledge-base/.

## Next steps (owner decides the order)
1. **Payment path throughput:** cut the DB checkouts per payment (one connection per request; make the idempotency `find` part of the accept transaction; batch `markDispatched`), and find out where the ~360 ms of YugabyteDB time goes (statement timings; WAL fsync on the Codespace disk; read-committed vs serializable per ADR-001). Then re-run the baseline.
2. **Footprint candidates, with the numbers above:** TigerBeetle dev sizing (2.2 GiB idle), Istio → Cilium mesh, Argo CD → Flux, native images for the JVM services, and Kyverno report sizing.
3. **DiscountAI (ADR-020):**
   - embedded DMN evaluation of the FR-01 rules;
   - an Aeron-based matching service around `BidBook`;
   - rail ports with simulators (ADR-018);
   - port the prototype's screens: ShopBot and Buying Partner PWAs, OME/audit micro-frontends.
4. **Platform section B:** QUIC hub, Tauri edge app, ML.
5. **Remaining:** profiles live, ADR-014 test suites, CI gates, ADR-017 resilience leftovers.

## Open questions for the owner
- `min_minor` collateral floors per vertical (₹500 placeholders).
- Confirm the v4.1 money letters (A charged at the merchant, B = A + Y + F paid by the buyer); the PRD has them swapped.
