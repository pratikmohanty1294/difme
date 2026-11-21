# Status and next steps

Updated 2026-11-18 (evening). Read this first when picking up work, then CONTRIBUTING.md.

## Where things stand
- **Running in the Codespace:** profiles `base`, `core` and `streaming`. Rebuilt on 2026-11-18 with the containerd image store on `/tmp`.
  - core: Cilium, Argo CD, Kyverno, Envoy Gateway, cert-manager, MetalLB, YugabyteDB, TigerBeetle, Valkey, the shared Postgres (ADR-019), Temporal, flagd and the core services.
  - streaming: Strimzi 0.51 / Kafka 4.2, Apicurio 3.3.4, Debezium 3.7.1 (both connectors RUNNING), RisingWave (meta on the shared Postgres), Flink operator and fraud-cep, KEDA, SeaweedFS.
  - Only `event-archive` is down, because it needs the lakehouse profile's Iceberg catalog.
- **All 13 profiles have converged on a cluster one by one** (base, core, observability, backoffice, platform-ops, streaming, streaming-extra, security, hub, process, lakehouse, lakehouse-extra, ml). Pending installs (2026-11-21): all profiles at once (run 5), onnx-serving/fraud scorer on a cluster, dbt lineage, dev memory halving merge (all need Actions); scale-to-zero (KEDA HTTP add-on) undecided until run 5.
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
- **Memory plan, progress:**
  - **Tier 2** (2026-11-19): the low-footprint JVM profile is applied to the Flink operator (−45%/−60%), the Strimzi cluster and topic operators (−24%/−22%) and Apicurio. `MALLOC_ARENA_MAX=2` is set on Kafka and Connect, `GOMEMLIMIT`/`GOGC` on the Argo CD controller and Temporal. Details: `docs/knowledge-base/cluster.md`.
  - **Tier 3:** `apache/kafka-native` cannot run under Strimzi, which needs its own broker images and agents. Adopting it would mean replacing Strimzi's broker with a plain StatefulSet; that is an owner decision. More of our own services as native images is still open.
  - **Tier 4:** no custom TigerBeetle build is needed. 0.16.78 has runtime flags (`--memory-lsm-compaction`, `--limit-pipeline-requests`, `--cache-grid`, `--development`); they are measured on a throwaway instance before the live ledger changes.
- **Threads and shared memory (owner request 2026-11-19):**
  - **Applied:**
    - `MALLOC_ARENA_MAX=2` (per-thread glibc arenas);
    - SerialGC and C1-only on control-plane JVMs (fewer GC and JIT threads);
    - 256–512k thread stacks;
    - virtual threads for blocking work;
    - allocation-free struct-of-arrays `BidBook`;
    - container-aware `GOMAXPROCS` and `GOMEMLIMIT`.
  - **Staged on `wip-netty`:** Netty 4.1.138 2+2 arenas with 1 MiB chunks (default 8+8 with 4 MiB) and 2 Vert.x event loops (default 8) for every Quarkus service, JVM and native.
  - **Planned:**
    - one shared CDS archive per JDK image and common base layers, so read-only class data and `libjvm.so` pages are shared in the page cache;
    - a KSM experiment: opt-in per process via `prctl(PR_SET_MEMORY_MERGE)` on the node's parent process; ksmd CPU cost to be measured;
    - for the DiscountAI matching service: Aeron shared-memory IPC (mmap log buffers), Agrona lock-free SPSC rings, cache-line padding against false sharing, and backoff idle strategies rather than pinned busy-spin on 4 cores.
- **TigerBeetle memory model** (from source and a throwaway instance):
  - The message pool is small (`--limit-pipeline-requests=0` saves ~90 MiB) and compaction memory has no effect.
  - The ~2 GiB is LSM table memory sized by batch size × 32 compaction ops across all trees, so `--limit-request` (start-time) is the lever.
- **Payment path, next:** an isolated `ysql_bench` replay of the accept transaction, comparing sharded vs colocated databases and SERIALIZABLE vs READ COMMITTED. It decides how to cut the ~360 ms of YugabyteDB time.
- **Payment path after tonight's fixes** (core + streaming, 2026-11-19, `ci/measure.sh`):

| Measure | Before the fixes | After |
|---|---|---|
| Sequential payment, warm | ~520 ms | p50 116 ms, p90 186 ms |
| 15 req/s | p50 551 ms, with 503 shedding | p50 **88 ms**, p95 274 ms, nothing shed |
| 200 req/s | 11.1 req/s accepted | **23.6 req/s accepted** (2.1×), p50 62 ms; node CPU peak 90%, so the next gains need less CPU per payment |

  - Fixes, by effect: flagd port collision, in-process flags, async dispatch, no redundant SELECT/UPDATE, Argo CD churn, Netty/thread limits.
- **Saga vs DIRECT A/B** (2026-11-19, same node, flag patched live and restored to saga afterwards):

| Load | Saga (today) | DIRECT (same-bank) |
|---|---|---|
| 15 req/s, p50 / p95 | 97 / 263 ms | 43 / 132 ms |
| 200 req/s, accepted | 33.4 req/s | 37.0 req/s (+11%) |

  - Temporal history CPU barely moves (~490m vs ~450m): it is mostly Temporal's baseline, not per-payment work.
  - Routing is the **owner's decision**; production keeps 100% saga.
- **BLOCKED 2026-11-19 ~10:15 IST:** GitHub refuses to start the Codespace: `HTTP 402: Usage of this codespace is currently disallowed: There is a billing issue`. The included Codespaces hours are likely used up.
  - The lakehouse install was in progress and must be re-run after the Codespace is back.
  - The cluster data survives the stop, and the startup heal (swap, ACL, Cilium refresh) runs automatically.
  - Staged and ready: `wip-security-prep` (istiod sizing, Keycloak JVM) and `wip-process-prep` (Fluxnova JVM).
  - Done: observability installed and healthy (~950 Mi); its fixes are on `main`.
- **Runtime move (owner decision 2026-11-20): a free-tier arm64 cloud VM** (arm64, 4 OCPU / 24 GB RAM, up to 200 GB disk, free with no time limit) replaces the blocked Codespace.
  - Setup: `ci/vm-bootstrap.sh` (idempotent; same `/workspaces/difme` path, image store and swap under `/var/lib/difme`, `difme-heal.service` at boot).
  - Driving it: `DIFME_VM=ubuntu@<ip> DIFME_VM_KEY=~/.ssh/<key> bash ci/cs.sh '<cmd>'`.
  - Images: all first-party images are multi-arch. Core services build per arch on native runners (native binaries and the AOT cache are CPU-specific), and `_manifest.yml` joins and signs the result. All directly referenced third-party images have arm64 builds.
  - Fixes found on the way: Warpgate `v0.13.2` never existed, so it is now 0.26.0 with first-start setup; Veraison publishes no images, so it is built from pinned source with a smoke test; Debezium needed the JDK CVE fix.
- **Installation plan** (owner-approved order): observability → lakehouse → process → security → ml → backoffice → hub → platform-ops. One profile at a time: install, verify healthy, record its footprint (`ci/mem-report.sh`), fix what breaks, then decide whether it stays up together with the rest or runs on demand.
- **Biggest single latency fix, 2026-11-19:**
  - **Cause:** a fixed ~520 ms per payment. Kubernetes' `FLAGD_PORT` service-link env put flagd on a random port, so every flag lookup waited out its 500 ms deadline.
  - **Fix:** flagd with explicit ports and service links off.
  - **Result:** sequential payments **520 ms → 43–100 ms**, even before cmd-svc's in-process flag resolver (rolling out) removes the remaining network call.
  - See `docs/knowledge-base/cluster.md`.
- **Payment path, done 2026-11-19** (rollout measurement pending):
  - The pg_stat_statements profile showed two avoidable round trips per payment: a SELECT before every INSERT and a full-row UPDATE after it. Both are removed, and `@DynamicUpdate` is on.
  - Dispatch moved off the request path (`AsyncDispatcher`). An inline Temporal start (~160 ms under load) had held each request's worker thread and limiter slot.
  - Our Quarkus services get Netty 2+2 arenas with 1 MiB chunks, 2 event loops and `MALLOC_ARENA_MAX=2`.
  - `rehydration-svc` is now a native image.
- **DiscountAI:**
  - `services/core/exchange-rules` (`PackRules`): embedded Fluxnova DMN 3.1.1 (Apache-2.0); the FR-01 pack is tested (7 tests). On `main`.
  - `services/core/exchange-matching` (`MatchingEngine`, `MatchingIntake`):
    - single-writer sealed-bid auction engine over `BidBook`;
    - lock-free many-to-one off-heap intake ring (Agrona 2.7.0) with backpressure on a full ring;
    - no hot-path allocation;
    - measured 117 ns per bid and p99 3 µs to rank/close a 61-bid auction (NFR-02: < 100 µs and < 5 ms).
  - `services/core/exchange-svc` :8089, on `main` with its image building in CI:
    - `POST /v1/exchange/{pack}/intents`: DMN eligibility; 422 with findings, or 202 with an auction id;
    - `POST /v1/exchange/auctions/{id}/bids` and `GET /v1/exchange/auctions/{id}`: winner plus hot backups;
    - packs on the classpath; one engine per pack under an AgentRunner with backoff;
    - 3 end-to-end tests; 25 tests across the exchange modules;
    - auction ids stay below 2^53 so JSON clients read them exactly.
  - **Award** (on `main`): the winner's split under the conservation identity (A, Y, F, B, buyer saving) and FR-14 collateral at close. The intent is in minor units, and Y_max = d·P − F − 1 is derived, not trusted from the client. Open owner questions in code: V = A for collateral; default partner tier ESTABLISHED.
  - **Evidence** (on `main`):
    - each auction gets a B-chain: intent + authorization, canonical JSON, SHA-256 links, Ed25519 operator seal plus witness;
    - `GET /v1/exchange/auctions/{id}/evidence` (with a verification verdict) and `GET /v1/exchange/keys`;
    - dev keys are generated at start. Open owner question: who witnesses in production, and where the operator key lives (OpenBao transit?).
  - Next: investor/mandate rules at bid time; an Aeron transport onto the same ring; rail simulators; PWAs.

## HANDOFF 2026-11-20 ~07:00 UTC (session limit reached) — START HERE
Everything is on GitHub: `main` plus the `wip-*` branches below. Rules and decisions: `docs/knowledge-base/owner-decisions.md`.
cluster-verify now fails fast (`FAILFAST` lines) instead of waiting out 30-minute deadlines.
Resume by checking each run below (`gh run view <id> --repo pratikmohanty1294/difme`), reading the per-profile
`CONVERGED / TIMEOUT / SYNC FAILURE` lines in its log (the run's own status can mislead), then doing the "next action".

| Branch | What it fixes | Run to check | Next action |
|---|---|---|---|
| `wip-formio-health` (**merged**) | **process CONVERGED** (44731058593: base, core, process 93 s). formio readiness on `/access`; diagnostics print startup lines, probe bodies, Pending pods | — | done |
| (main) ml, streaming-extra | **streaming-extra CONVERGED** (44733260505, SeaTunnel 73 s). ml run 44733255772 still running (expected to fail on missing feast/fraud-scorer images; wip-ml fixes) | 44733255772 | merge Marquez + wip-ml when it ends |
| `wip-marquez` (**merged**) | lakehouse-extra verified (Marquez ~240 MiB) | — | OpenLineage emitters next |
| `wip-trino-only` | **Owner: Trino only.** StarRocks removed (chart, setup job, SQL, secret); qry-svc fraud analytics query moved to the Trino datasource; MariaDB driver dropped | 44734645270 `core,streaming,lakehouse` (+ core-services CI for qry-svc) | converged → merge (supersedes wip-lakehouse-fix) |
| `wip-trino-only` (layer 4) | Trino crash = the S3 client SDK needs a region (`s3.region=us-east-1`, SeaweedFS ignores it); RisingWave request 1Gi→384Mi (used 71), yb-master 512→192Mi (used 95). Pushed during 44734645270 → re-run clean after it | next run | converged → merge |
| `wip-trino-only` (layer 5) | Trino starts (region fix). dbt: Polaris schema never created (relational-jdbc needs the admin-tool bootstrap) → Polaris moved to the shared Postgres + `polaris-bootstrap` Job. superset-worker waited for the disabled chart Redis → waits for Valkey | 44741294208 `core,streaming,lakehouse` | converged → merge |
| `wip-trino-only` (layer 6) | **lakehouse CONVERGED** (44746488055: base, core, streaming, lakehouse 138 s) → **all 13 profiles verified** | background job: merge trino-only + openlineage + iceberg-maint after idle run 44748980093, then images, then `core,streaming,lakehouse,lakehouse-extra` on main | converged → lineage + maintenance verified |
| (main) lakehouse + lineage + maintenance | **Merged and CONVERGED on main** (44758053216: base, core, streaming, lakehouse 124 s, lakehouse-extra 41 s; images 44755227155 green). Trino 755 MiB, Polaris 192, Superset 187 + worker 107, Marquez 249. Lineage flow not yet proven (convergence ≠ events in Marquez) → verify-profile now prints Marquez namespaces/jobs | next lakehouse-extra run (settle so dbt's 15-min cron fires) | jobs listed → lineage verified |
| `wip-openlineage` (on wip-trino-only) | Trino event listener, dbt via `dbt-ol`, **Flink 2 job-status listener** (openlineage-flink 1.53.0 in the Flink image) → Marquez | after wip-trino-only merges + image rebuild | verify lakehouse + lakehouse-extra |
| `wip-iceberg-maint` (on wip-trino-only) | hourly Iceberg optimize + 7-day snapshot expiry + orphan cleanup for the append-only archive tables (2 s commits make small files) | after wip-trino-only | verify with lakehouse |
| `wip-io-pass` (**closed, not merged**) | A/B 2 (44745730975 vs 44745795543): fraud-cep JobManager restarted/young on the branch and read+wrote 8.7 vs 2.0 MB/s → incremental checkpoints dropped (no benefit shown, signs of harm). YB heartbeat dropped (no effect). Report improvements merged to main. **Method gap:** the report runs right after convergence, so since-start averages are startup I/O; an idle I/O measurement needs a settle period (next: optional settle wait before the process report) | — | — |
| idle baseline (main) | 44748980093 `core,streaming`, settle 600 s: idle disk is small (max ~60 KB/s, Postgres/YB writes); idle CPU 55 % of a core, top = yb-tserver 7 % + yb-master 6.3 % (RPC threads), and they exchange ~270 KB/s each way | — | — |
| `wip-yb-shards` vs `wip-yb-base` (**dropped**) | one tablet per table, no splitting: yb CPU 8.1+8.5 % vs 12.7+9.7 % (single 15 s window, noisy) but tserver↔master traffic 700/657 vs 635/574 KB/s and memory equal → no clear win, dev would diverge from production. Neither heartbeats nor tablet count explain the chatter | — | next idea needs YB's own metrics (/metrics RPC counters) rather than guesses |
| `wip-evidence-overlay` (**merged**) | Real cause (44784624271): Kyverno blocked the restart Job (no cost labels; kubectl image not from the DIFME registry, required in core-platform) — also why the PostSync hook never ran. Job now in `security` with labels; RBAC still only get/patch on exchange-svc. My plain-Job change briefly broke security on main and all-at-once run 2 | 44794273284 `core,security` ✅ (published keys `exchange-svc`, `notary-1`; notary witness: yes) | done |
| `wip-standing-mandates` (**merged**) | Standing mandates are resting orders: when an intent opens, the exchange bids for every eligible standing mandate at its yield floor through the same investor_eligibility rules (the 50 ms window is too short for a polling app); `GET /{pack}/mandates/{id}` (budget left, awards, yield, tier). 12 tests pass | images on main | — |
| `wip-pwa` (**merged**) | **PWAs served**: core converged with the three PWA apps (44774062381; images 44768956515 green) | — | live buy through the PWA path next |
| `wip-all-at-once` | Run 1 (44773866522): core, security, hub, observability CONVERGED together (free RAM 8.7 GB after core → ~5.8 GB after observability); streaming FAILFAST on Apicurio: duplicate version rows on YugabyteDB under load ("legacy 2.x database"), the 6th tool hit by YugabyteDB migration races → Apicurio moved to the shared Postgres (`wip-apicurio-pg`, merged into this branch). During streaming: 4.7 GB free, 2.8 GB swapped | run 2: 44787057993 (ETA ~2.5 h) | see how far memory carries → decide scale-to-zero for idle UIs/analytics |
| **BLOCKER (owner)** | Since 2026-11-20 22:04 UTC GitHub Actions refuses every job with "owner invocation blocked" (annotation on runs 44822964648, 44825799751, images 44825798912); pushes after that start no runs at all. Account-level (billing/usage or policy), not the code. Unverified until it clears: all-at-once run 5, ml + fraud-scorer in cluster, FAIL path in cluster, lint fix 0680301 | — | owner: check github.com → Settings → Billing and plans / Actions, and the repo's Actions tab; then re-dispatch run 5 and `core,lakehouse,ml` |
| (main, in-session) Feast feature data | dbt `models/features/account_velocity` + `device_geo` (→ `iceberg.features.*`, Feast's offline sources); staging adds debit/credit account; EventArchiveJob archives `edge.telemetry.v1` → `events.edge_scores` (no producer yet: device features stay empty until the edge app sends scores). Edge score converted (1 − p) × 1000 to the model's trust scale. Verified: Trino compile; DuckDB run + 12 value checks (`tests_local/features_duckdb.py`, in the dbt workflow); full chain dbt → Feast 0.67 → fraud scorer: normal order ALLOW 993, new device + 18σ amount BLOCK 90 | — | EventArchiveJob SQL and dbt on Trino at the next lakehouse cluster run |
| (main, verified in-session without Actions) | JDK 25 from conda-forge → services/core exchange tests pass here (46). Real exchange-svc + real fraud scorer + real Feast 0.67 run in the session: browser live buy against the **real** exchange passes (`EXCHANGE_URL=… node e2e/live-buy.cjs`; amounts ₹8,520 / saved ₹1,480 match the exchange's split); FAIL path: safe → `server:fraud-gbt-0.1.0`, risky (client claimed 999) → `RISK_TOO_LOW`, scorer down → `RISK_UNSCORED` | — | — |
| (main, Actions blocked → verified locally) | **CI usage:** images build only what changed since the last green images run (+ `verticals/` now rebuilds exchange-svc); cluster-verify one run at a time repo-wide. **Fraud scorer:** `POST /v1/score/order` (accountId, deviceId, amountMinor) reads the Feast online store (tested against a real Feast 0.67 `feast serve`: ~7 ms/order, 29 MiB; Feast returns columns out of request order → mapped by name; missing features defaulted and listed). **PWA:** browser test `pwa/discountai/e2e/live-buy.cjs` (Playwright, recorded exchange) passes bought + refused paths; found and fixed in live builds: prototype outcome picker and "All data simulated" labels, a browser `/api/rules` pre-check that 404s on the live stack, missing escrow-hold step | local only | run `pwa`, `fraud-scorer`, `images` workflows once Actions is back |
| (main) YB idle chatter | Debezium streams → ~200 master catalog Read/s (0 when stopped). Walsender idle poll flag applied but no effect (source honours it) → reverted; server-side CDC stream activity, ~12 % of a core. Open, low priority | 44819780793 | — |
| `wip-live-audit` | Exchange PWA: live evidence panel (auction → server chain + verdict) | tsc + vite build ✅ | merge after images 44819712504 (a pwa push would cancel it) |
| (main) live-buy smoke ✅ | Through the shopbot PWA proxy (44819780793): mandate → intent (1 standing bid) → award (A 16,000 Y 640 F 200) → bought → evidence verifies (hashes, links, seal, witness) → delivered (A+F released, Y after return window), ~0.3 s | — | done |
| `wip-fail-path` (**merged**) | §11.1 FAIL path: an intent carrying `fraudFeatures` (no attested edge score) is scored by the exchange via `onnx-serving`; the server score replaces the client's (`riskSource: server:<model>` in the response and evidence). Scorer down/slow → 422 `RISK_UNSCORED` (fails closed; a 5xx tripped the API contract check) | core-services 44823938643 ✅ | cluster check with core+ml |
| `wip-fraud-scorer` (**merged**) | Fraud scorer (PDF §5.7): `ml/fraud/train.py` (60 depth-3 GBT trees on the Feast feature set, synthetic labels from the §7.4 rules until real orders exist; ROC AUC 0.88, 32 KB ONNX) + `services/fraud-scorer` (Rust, ONNX Runtime 1.31.1 loaded from the official wheel's lib; `POST /v1/score` → probability, riskScore 0..1000 higher=safer, ALLOW/REVIEW/BLOCK). 36 MiB RSS, 1 thread. `onnx-serving` back in the ml profile | fraud-scorer CI 44815544195 ✅; images ✅ 44822873486 (after manylinux_2_28 + `packaging` fixes; smoke scores an order) | ml verify; next: Feast online lookup by account/device id, MLflow-promoted models, exchange calls it on attestation FAIL |
| `wip-sim-delivery` (**merged**) | Simulated merchant + carrier (`SimParties`, own Ed25519 keys registered like real parties) sign invoice + DELIVERED event; `POST /v1/exchange/auctions/{id}/sim/delivery` (off with `difme.exchange.sim-parties=false`). PWA live buy now runs intent → auction → execute → evidence → delivery (fee released, yield date shown) | core-services 44812125832 ✅ (13 tests) | live buy through the PWA on a cluster |
| `wip-all-at-once` run 4 | run 3 (44794279398): base→core→security→hub→observability→streaming→streaming-extra→process→backoffice CONVERGED (9 profiles; free RAM 4.3–5.1 GB, 4.8 GB swapped, memory requests 74%). Stopped at lakehouse, **not on memory**: (1) kubelet 110-pod limit (`Too many pods`, superset/event-archive Pending) → `maxPods: 240` + Cilium /23 per node; (2) shared Postgres out of connections (Polaris bootstrap EOF at SSL handshake) → `max_connections=300` via `05-settings.sql`; (3) Argo CD controller 5.3 cores (node CPU 95%): GOMEMLIMIT 350 MiB below its ~420 MiB working set → GC spiral → 700 MiB | next run | lakehouse, lakehouse-extra, ml, platform-ops; settle + 200 req/s; dbt lineage |
| `wip-pg-pools` vs `wip-pg-base` (**dropped**) | Postgres processes 56→27 (−44 MiB PSS) but accepted 6,439→3,563 and p99 352→515 ms: Temporal starved for connections. Not merged | — | — |
| docs | ADR-021 written (Marquez, Trino only, pgvector, minimal Feast; third-party migrations on the shared Postgres); README stack table updated | — | done |
| `wip-ml` (**merged**) | **ml CONVERGED** (44745510825, 62 s): Feast 0.67.0 (CVE fix) with registry apply, MLflow on the shared Postgres, pgvector database `ml`. onnx-serving parked (no scorer code/model) | — | done; scorer is product work |
| `wip-security-dev` (**merged**) | **security + hub CONVERGED** (44727343332: base, core, security 829 s, hub 12 s). istio-cni (Cilium dirs + exclusive=false), Istio webhook ignore rules, OpenBao 1 replica + static seal + self-init incl. evidence-transit, SPIRE, Veraison, attestation verifier, notary | — | done |
| `wip-tier-history` (**merged** d345479) | tier from history, evidence custody (transit + notary signers), `services/notary`; merged with the docker.io mirror and the refault report | images 44715598131 ✅ (mirror.gcr.io works; notary image built) | images green → add the OpenBao `evidence-transit` stanza to wip-security-dev (draft in scratchpad: mount, ed25519 key, policy, role for core-platform/exchange-svc) |
| `wip-lakehouse-fix` | Layer 2 held (StarRocks CR accepted, Superset/Polaris OK). Layer 3: StarRocks FE and event-archive unschedulable (Insufficient memory: requests, not usage) → lakehouse requests trimmed (limits unchanged); Trino still crashes, error hidden by its config dump → diagnostics now print previous-run ERROR/Caused-by lines and a requested-vs-used memory table | 44731237973 `core,streaming,lakehouse` | read Trino error + request table → fix |
| `wip-mem-batch2` | **Rejected.** Kyverno Go rule broke sync (same as malloc-arena); the JVM-only re-test (44701164308) gave 5,581 → 5,575 MiB PSS, −31 threads, p99 297 → 339 ms: not better than the baseline | — | Go/JVM runtime limits per component instead (e.g. GOMEMLIMIT/GOMAXPROCS in each chart's values) |
| `wip-malloc-arena` | **Rejected** (A/B 44687672715): the Kyverno `+(value)` env injection corrupted containers that use `valueFrom` (temporal-frontend invalid; base/core sync failed). See knowledge-base/security.md. | — | redo with a foreach + precondition + JSON patch, or per component |
| `wip-hub` (merged) | **hub verified** with security (44727343332) | — | done |

**Decided business rules to implement:** partner tier from history (COLD_START until verified deliveries); evidence operator key in OpenBao transit + independent notary witness.
**Memory batch 3 (`wip-mem-batch3`):** Temporal 4 history shards: temporal-history 152 → 46 MiB PSS (anon 134 → 29); core total 5614 → 5450 MiB. Latency A/B (44715451747 vs main 44715456360): p50/p95/p99 14.7/220/445 vs 12.5/232/416 ms, accepted 4633 vs 4237 → **keep**. **Merged** (no main run live). Both legs shed ~50 % with 503 at 200 req/s; accepted 56–61/s vs 75–84/s earlier — variance, but watch it.
**Next memory candidates (from the per-process report):** Temporal SQL pool sizes (shared-Postgres backends), Cilium's
embedded Envoy workers (4), Argo CD controller GOMEMLIMIT. Then CPU → disk I/O (cmd-svc reads ~100 KB/s at idle) → network.
**Waiting on the owner:** PC memory notes + session logs upload; DiscountAI prototype source (for the PWA screens);
open decisions in owner-decisions.md (canary SLO thresholds, saga vs DIRECT, collateral floors, return window 7 d
placeholder, reputation cold start and V_ref). Revoke the GitHub token pasted in chat on 11-18/11-20.

## PROGRESS LOG (cloud session, 2026-11-20) — read this first; updated after every step
How to resume after any stoppage (runner loss, network, session end): every change lives on a pushed `wip-*` branch;
results are in each run's log (streamed live, kept even if the run dies). Re-dispatch a run with
`gh workflow run cluster-verify.yml --ref <branch> -f profiles=<list>` (add `-f measure='200 60s'` for the baseline).

**Merged to main today:** formio open-source shim; cmd-svc replicas left to the canary Rollout (observability ✅);
Actions parity (gates on `wip-*` pushes, properties gate, `measure` input); owner rules/decisions log
(`docs/knowledge-base/owner-decisions.md`); Fluxnova TCP probe; formio inputmask 5.0.9 pin; formio VM bundle build;
backoffice writable /tmp (backoffice ✅); platform-ops fixes incl. Harbor on the shared Postgres (platform-ops ✅);
cluster-verify Summary-step fix (runs had been marked failed when no baseline ran: read the per-profile CONVERGED lines).

**Verified converged:** base, core, observability, backoffice, platform-ops, streaming.
**Merged today (later):** ADR-014 Schemathesis contract gate; FR-01 money loop (rails, execute, proof of delivery, settle, FR-16 reputation, OpenAPI contract); `ci/proc-report.sh` (memory, CPU, disk, network per process/thread; runs after every profile); Makefile build/test fixes.
**Optimisation order (owner):** memory → CPU → disk I/O → network I/O.
**Memory wins kept:** TigerBeetle 2170 → 1373 MiB; batch 1 (Envoy 1 worker, Kyverno reports off): −333 MiB PSS and idle CPU 166 % → 18 % of a core. Both A/B-verified, no throughput/latency regression.
**Merged (later still):** QUIC hub (Rust, 6 MiB RSS); Maven mirror for all Maven workflows.

**In flight — branch → what it fixes → verify run → next action**
| Branch | Fix | Run | When green |
|---|---|---|---|
| main (formio VM bundles) | process profile | `core,process` queued after the main image build | process ✅ |
| `wip-streaming-archive` | event-archive moved to lakehouse (`flink-archive`); verify-profile diagnostics | 44682308371 `core,streaming,lakehouse,ml` | merge; then extras (`streaming-extra`, `lakehouse-extra`) |
| `wip-security-dev` | OpenBao 1 replica, static seal, self-init; SPIRE hooks off + insecureSkipVerify; istio-system privileged; Veraison stores; verifier out | 44682927471 `core,security` | merge; then `hub` stays blocked (no code) |
| `wip-verify-diag` | diagnostics only (also cherry-picked into the two branches above) | — | lands with either |
| `wip-malloc-arena` | Kyverno default `MALLOC_ARENA_MAX=2` for every container | A/B 44687672715 vs main 44687637776 (`core` + `measure 200 60s` + proc-report) | merge only if it beats main |
| `wip-mem-batch2` | Go runtime defaults (GOMAXPROCS=2, GOGC=50 where unset) + JVM ActiveProcessorCount=2 | A/B 44690021537 vs main 44690017534 | merge only if it beats main |
| `wip-attestation` | Rust RATS verifier (nonce, COSE EAT, Wasm hash, revocation; fail-closed); 3.7 MiB RSS; back in the security profile | attestation workflow | merge after `wip-security-dev` (resolve: keep verifier.yaml in the attestation kustomization) |
| `wip-formio-auth` | formio → FerretDB 2 with SCRAM credentials (process profile: next blocker after the VM bundles) | 44689378187 `core,process` | merge when green |

**Waiting on the owner:** PC memory notes + session logs upload (to complete `owner-decisions.md`); canary SLO
thresholds and the other open questions in `owner-decisions.md`.

**Blocked:** nothing code-wise; hub and attestation-verifier now have code on their branches.

## HANDOFF 2026-11-20 08:10 IST: continue from a cloud session
**Owner rule (hard):** nothing runs on the owner's PC: no builds, installs, scans, watchers, git or gh. Work from a claude.ai/code cloud session, GitHub Actions, or the runtime VM/Codespace only. The PC hit critical memory from local npm installs and watch loops.

**How work is verified without a runtime:**
- The manual workflow `cluster-verify.yml` runs on a free 4 vCPU / 16 GB runner (inputs: `profiles` e.g. `core,observability`, `runner` = `ubuntu-24.04` or `ubuntu-24.04-arm`, `deadline`).
- It runs `make cluster` → base → each profile via `ci/verify-profile.sh`, and Argo CD tracks the dispatched branch.
- Results land in the run summary and the `cluster-report-*` artifact. Independent runs can go in parallel.

**Verified so far:** base and core converge from scratch on x64 and arm64 (core about 2.5 min, about 4.3 GB used, 7.7–8 GB free of 16).

**In flight (as of 08:10, superseded by the progress log above):**
- Branch `wip-formio-oss` (not merged), formio made open source only (owner chose "shim it out"):
  - an MIT stand-in for the proprietary `@formio/feature-flags` (in `vendor/formio/server/shims/`);
  - `@formio/vm` (OSL-3.0) and `@formio/nextgen` (ISC) declared;
  - `ckeditor5` overridden from the commercial 47.x "LTS Edition" to the GPL-2.0-or-later 49.1.0 line;
  - a lockfile plus `npm ci`.
  - A full licence scan of the 577 production packages came back clean.
  - Also on this branch: the `canary-gates` no-traffic fix (merged from `wip-canary-nodata`) and better not-ready diagnostics in `ci/verify-profile.sh`.
  - Check runs: images 44654907680 and cluster-verify (core,observability) 44654967897. Merge when green; after `main` rebuilds formio, verify `core,process`.
- **Observability:** after the canary fix, the new `quarkus-cmd-svc` revision never turns ready, and its pods are replaced about once a minute. Run 44654967897 carries the diagnostics. Find the cause (pod-template change from the observability profile? probe timing?), then fix it.
- **Process:** formio was crash-looping on the missing `@formio/vm` (fixed by the branch above). Fluxnova runs but never turns ready (logs not yet captured; check the new JVM options `-Xmx512m`, the readiness probe and DB connectivity). `deploy-process-models` waits on Fluxnova.
- **Canary SLO thresholds:** `slo-gate` requires p99 ≤ 5 ms, but measured cmd-svc p99 is about 250–330 ms, so every real rollout would abort. **Owner decision needed.**

**Runtime status:**
- **Cloud VM** (free trial, single availability domain): VM `difme-runtime` (A1.Flex 4/24, Ubuntu 24.04, 150 GB) was refused with "Out of capacity". The in-browser retry loop stopped when the console session expired; it needs the owner to sign in again. The SSH public key lives on the owner's PC.
- **Codespace** `difme-dev-97rgj4pqrvpjh6j9`: still HTTP 402 (billing). Retention runs to 2026-12-19; any successful start resets it. A cloud session can retry `gh codespace ssh` once the monthly hours reset.

**CI lessons applied** (see `.github/workflows`):
- Release tags (`:0.1.0`) move only on `main`; branch builds push `sha-*` tags only.
- Kyverno trusts `_image.yml` or `_manifest.yml` signatures from `main` (`subjectRegExp`).
- Maven resolves through a public Central mirror (repo1 returned HTTP 429 to runners).
- The backoffice and formio builds use lockfiles with frozen installs.

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
The full list, with context and every decision already made, is in [knowledge-base/owner-decisions.md](knowledge-base/owner-decisions.md):
- canary SLO thresholds (p99 ≤ 5 ms vs measured 250–330 ms);
- payment routing, saga vs DIRECT;
- evidence witness and operator-key custody;
- collateral V = A and the default partner tier;
- `min_minor` collateral floors per vertical (₹500 placeholders);
- the v4.1 money letters (A charged at the merchant, B = A + Y + F paid by the buyer; the PRD has them swapped);
- Istio → Cilium mesh, Argo CD → Flux;
- Kafka native image vs Strimzi.
