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
- **Runtime move (owner decision 2026-11-20): Oracle Cloud Always Free Ampere A1** (arm64, 4 OCPU / 24 GB RAM, up to 200 GB disk, free with no time limit) replaces the blocked Codespace.
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
| `wip-formio-health` | formio now starts (resourcejs fix worked) but `/health` answers 400: the schema lock is missing or mismatched, and formio swallows db-setup errors at start. Branch adds DEBUG=formio:db,formio:update and verify-profile prints failing readiness-probe bodies | 44715359715 `core,process` | read the debug lines + /health body → fix the failing setup step (likely a FerretDB-unsupported call) |
| `wip-security-dev` | istio-cni root cause (run 44708558896 node listing): Cilium's live conflist is /etc/cni/net.d (k3s dir empty), so `platform: k3d` was wrong; and Cilium cni.exclusive=true strips istio-cni's chained entry. Fixed both; OpenBao `evidence-transit` self-init added (ed25519 non-exportable key, exchange-evidence policy + k8s role). Still open: istio-base/istiod OutOfSync drift | 44718018139 `core,security` | istio-cni ready → look at the istiod/istio-base drift; converged → merge |
| `wip-tier-history` (**merged** d345479) | tier from history, evidence custody (transit + notary signers), `services/notary`; merged with the Docker Hub mirror and the refault report | images run on main (also first test of mirror.gcr.io + notary image) | images green → add the OpenBao `evidence-transit` stanza to wip-security-dev (draft in scratchpad: mount, ed25519 key, policy, role for core-platform/exchange-svc) |
| `wip-lakehouse-fix` | StarRocks secret + runAsNonRoot true. Run 44708424432 lost its runner at 64 min (2nd time on this chain; log gone). Branch now also carries `wip-runner-guard` (runner agent OOM-exempt, CPU/IO priority) | 44717717115 `core,streaming,lakehouse` | finishes → read lakehouse verdict; if the runner survives, merge wip-runner-guard to main |
| `wip-mem-batch2` | **Rejected.** Kyverno Go rule broke sync (same as malloc-arena); the JVM-only re-test (44701164308) gave 5,581 → 5,575 MiB PSS, −31 threads, p99 297 → 339 ms: not better than the baseline | — | Go/JVM runtime limits per component instead (e.g. GOMEMLIMIT/GOMAXPROCS in each chart's values) |
| `wip-malloc-arena` | **Rejected** (A/B 44687672715): the Kyverno `+(value)` env injection corrupted containers that use `valueFrom` (temporal-frontend invalid; base/core sync failed). See knowledge-base/security.md. | — | redo with a foreach + precondition + JSON patch, or per component |
| `wip-hub` (merged) | QUIC hub | main image build (latest images run on main) | verify `core,hub` profile once `difme-quinn-hub:0.1.0` exists |

**Decided business rules to implement:** partner tier from history (COLD_START until verified deliveries); evidence operator key in OpenBao transit + independent notary witness.
**Memory batch 3 (`wip-mem-batch3`):** Temporal 4 history shards: temporal-history 152 → 46 MiB PSS (anon 134 → 29); core total 5614 → 5450 MiB. Latency A/B re-run (44715451747 vs main 44715456360, `-f measure='200 60s'` — `measure=true` is not a rate) before merging.
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
- **Oracle Cloud** (Free Trial, Hyderabad, single AD): VM `difme-runtime` (A1.Flex 4/24, Ubuntu 24.04, 150 GB) was refused with "Out of capacity". The in-browser retry loop stopped when the console session expired; it needs the owner to sign in again. The SSH public key lives on the owner's PC.
- **Codespace** `difme-dev-97rgj4pqrvpjh6j9`: still HTTP 402 (billing). Retention runs to 2026-12-19; any successful start resets it. A cloud session can retry `gh codespace ssh` once the monthly hours reset.

**CI lessons applied** (see `.github/workflows`):
- Release tags (`:0.1.0`) move only on `main`; branch builds push `sha-*` tags only.
- Kyverno trusts `_image.yml` or `_manifest.yml` signatures from `main` (`subjectRegExp`).
- Maven resolves through Google's Central mirror (repo1 returned HTTP 429 to runners).
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
