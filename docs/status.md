# Status and next steps

Updated 2026-11-18 (evening). Read this first when picking up work, then CONTRIBUTING.md.

## Where things stand
- **Running in the Codespace:** profiles `base`, `core` and `streaming`. Rebuilt on 2026-11-18 with the containerd image store on `/tmp`.
  - core: Cilium, Argo CD, Kyverno, Envoy Gateway, cert-manager, MetalLB, YugabyteDB, TigerBeetle, Valkey, the shared Postgres (ADR-019), Temporal, flagd and the core services.
  - streaming: Strimzi 0.51 / Kafka 4.2, Apicurio 3.3.4, Debezium 3.7.1 (both connectors RUNNING), RisingWave (meta on the shared Postgres), Flink operator and fraud-cep, KEDA, SeaweedFS.
  - Only `event-archive` is down, because it needs the lakehouse profile's Iceberg catalog.
- **Built, not yet deployed live:** lakehouse, ml, observability, security, process, backoffice, hub.
- **First real streaming install:** it surfaced about 20 defects, all fixed. See `docs/knowledge-base/gitops.md` and `cluster.md`. The worst: SeaweedFS reserved about 21 GB of disk and caused an eviction storm.
- **DIFME/DiscountAI (ADR-020):** `services/core/exchange-kernel` (scoring, collateral, slashing, reputation, conservation identity, evidence chains; 8 tests) and `verticals/` (FR-01 live pack, FR-02…09 declared).

## Baseline: payment write path, core only (`ci/measure.sh 200 60s`, before DispatchMarker)
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

## Footprint: core + streaming (2026-11-18, idle)
| Namespace | RAM | CPU used |
|---|---|---|
| streaming | 4.4 GiB | 0.28 cores |
| database-tier | 2.9 GiB (TigerBeetle 2.2) | 0.31 |
| core-platform | 1.0 GiB | 0.02 |
| ingress-system | 0.6 GiB | 0.03 |
| rest | 1.2 GiB | 0.47 |
| **pods total** | **10.1 GiB** (node 13.2 GiB of 16) | node peak under load 3.2 of 4 cores |

- **CPU requests are the binding limit:** 3,980m of 4,000m are reserved while real use is ~1.3 cores idle. The remaining profiles will not schedule until requests are right-sized.
- **Biggest reservations:** yb-tserver 300m; fraud-cep TaskManager 300m; RisingWave, Kafka, yb-master, TigerBeetle and the Flink JobManagers 200m each; KEDA min 2 replicas of projection-svc and rehydration-svc.

## Payment path, core + streaming (`ci/measure.sh 200 60s`, after DispatchMarker)
- **Throughput:** 8.5 req/s accepted (202) and 152 req/s shed (503). Steady-state p50 is 14.6 ms and p95 176 ms. Node CPU peaks at 3.2 cores.
- **Not comparable with the core-only baseline below:** streaming adds CPU contention (Temporal history peaked at 389m), and several pods restarted during the run (Debezium Connect, the Strimzi entity operator, Temporal pods recovering from the evictions).
- **To isolate the DispatchMarker change**, re-measure on a quiet node, either with core only or after right-sizing.

## Memory and CPU after the 2026-11-18 rebuild (core + streaming)
- **Memory ceiling:** the Codespaces host runs **earlyoom**, which SIGTERMs the largest process when RAM *and* swap are both below ~10%. That caused the Argo CD controller restart loop and the TigerBeetle startup kills. See `docs/knowledge-base/cluster.md`.
- **Fixes applied:**
  - **Trims:** Debezium Connect 885 → 406 Mi; Flink operator 600 → 464 Mi; entity operator without the user operator; Kafka heap 512m.
  - **Dev sizing:** CPU requests only.
  - **Tier 1 (zswap + swap + kubelet LimitedSwap):** available RAM 2.4 → **5.3 GB**, with zswap compressing at about 17:1.
- **Load test** (200 req/s, core + streaming, after Tier 1):
  - 6.6 req/s accepted (202), 154 req/s shed (503); p50 32 ms, p95 259 ms.
  - Node CPU was **64% at idle**, peaking at 89%.
  - No swap thrashing (si/so ≈ 0).
- **Argo CD churn fixed (2026-11-19):**
  - **Causes:** two apps owned the Flink job RBAC, so each sync re-triggered the other about every 2 s. Separately, `replicas: 1` on KEDA-scaled Deployments fought the HPA.
  - **Effect at idle:**

| Measure | Before | After |
|---|---|---|
| Node CPU | 2.4–2.9 cores | 0.6–0.9 cores |
| k3s | up to 260% | under 20% |
| Argo CD controller | 550m | ~10m |
| Reconciles per 5 min | 313 | 46 |
| Automated syncs per 5 min | 166 | 8 |

  - **Load test afterwards:** **10.3 req/s accepted** (from 6.6); p50 3.1 ms; node idle 19%, peak 86%; no pod restarts during the run.
- **Previously the limiting factor was control-plane CPU at idle:** `k3s` (API server, datastore, controllers) ~1.1 cores and 1.8 GB RSS; Argo CD controller ~0.5 cores.
  - Suspects: Argo CD reconcile churn, with server-side diff on every pass and 4 apps stuck OutOfSync (envoy-gateway, flink-operator, stream-services, flink-jobs).
  - Proposed next: fix the OutOfSync diffs, lengthen the resync period, and measure k3s CPU before and after.
- **Memory plan, still to do:** Tier 2 (JVM/Go runtime flags); Tier 3 (Kafka native image, more native services); Tier 4 (TigerBeetle dev build plus matching Java client).

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
