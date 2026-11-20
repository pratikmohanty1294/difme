# Performance and efficiency (measured, not assumed)

### Bulk file job (ADR-011), 10M records with DB + DMN validation, 4-vCPU CI runner
| Version | Time | Throughput |
|---|---|---|
| First | 855 s | 11,695 rec/s |
| Optimised | **56 s** | **178,571 rec/s** |

What made the difference:
1. A JVM-wide shared account snapshot: one scan, zero per-record queries.
2. A memoised DMN: 9 distinct inputs instead of 10M evaluations.
3. Flat POJO records instead of Kryo.
4. Dropping the raw line before the shuffle.
5. Per-partition report counts.
6. Batch lookups cut at exactly 1,000 keys.

Determinism: "lowest offset wins" duplicate handling, so repeat runs give identical counts.

### Base profile (13 apps), Codespace 4 cores / 16 GB
Argo CD controller: 300–900m → **4–6m**. Gatekeeper: 120–400m → **~5m**. Node idle: **0.22–0.68 cores, ~4.9 GB**.

### Service runtimes (ADR-012)
- **Native (Mandrel):** rate-limiter, consistency-router, projection-svc, replay-engine.
- **JVM 25 + AOT cache:** cmd, qry, saga, rehydration, process-gateway.

### TigerBeetle dev sizing: 2151 → 1356 MiB (measured 2026-11-20, 0.16.78)
Method: a throwaway replica in a cloud container, formatted fresh per variant, RSS read from `/proc` after start (TigerBeetle allocates statically, so RSS doesn't grow with load). The old flags reproduced the pod's 2.1 GiB.

| Flags (on top of `--experimental`) | RSS |
|---|---|
| grid 128 MiB, accounts 32, transfers 32, pending 16 (old) | 2151 MiB |
| + `--development` (format and start) | 1641 MiB |
| + `--memory-lsm-manifest=1MiB`, grid 32 MiB | 1415 MiB |
| + object caches 8/8/4 MiB (**adopted**) | 1356 MiB |
| object caches 0 (floor) | 1334 MiB |

`--memory-lsm-compaction` can't go below 10.5 MiB and changes nothing under `--development`; `--limit-request=64KiB` *raises* memory there. Kept only if the payment baseline (A/B on identical runners) doesn't regress.
**A/B result (2026-11-20, `core` + `measure 200 60s`, same-time runs 44684890534 vs 44684975198):** TigerBeetle 2170 → 1373 MiB, node peak RAM 9130 → 8343 MiB; accepted 58.5 → 84.3 req/s, p50 38 → 32 ms, p99 346 → 338 ms. Runner variance is large (an earlier main run reached 91 req/s), so throughput is read as "no regression". **Kept.**

### QUIC hub footprint (measured 2026-11-20, release build)
4.2 MiB binary, 6 MiB RSS and 3 threads at idle (2 tokio workers via `HUB_WORKER_THREADS`, hand-rolled atomic metrics, no metrics crate). Pod request/limit set to 24/96 Mi (was 64/256 Mi, written before the code existed).

### Memory batch 1: Envoy one worker + Kyverno reports controller off (A/B 2026-11-20, kept)
Same-time `core` runs with the per-process report (main 44687637776 vs branch 44687640849):

| | main | batch 1 |
|---|---|---|
| PSS, all pod processes (base + core) | 5,922 MiB | 5,589 MiB |
| process CPU at idle, all pods | 166 % of a core | 18 % |
| node CPU at idle | 809m | 297m |
| Envoy proxy | 324 MiB, 4 workers | 242 MiB, 1 worker |
| Kyverno reports controller | 251 MiB, 141 % CPU, 35 KB/s disk read | — |
| load test: accepted / p50 / p99 | 59/s, 35 ms, 321 ms | 75/s, 25 ms, 313 ms |

The reports controller was the single largest idle CPU consumer in the cluster, writing PolicyReports nobody reads. Next candidates seen in the same report: Cilium's embedded Envoy also runs 4 workers (`wrk:worker_×4`).
### Attestation verifier footprint (measured 2026-11-20, release build)
1.8 MiB binary, 3.7 MiB RSS and 2 threads at idle (one tokio worker via `VERIFIER_WORKER_THREADS`). Pod request/limit 16/64 Mi (was 32/128 Mi, written before the code existed).
### Temporal history shards (2026-11-20, A/B on wip-mem-batch3)
The temporal chart 0.62.0 defaults to `numHistoryShards: 512`; each shard keeps its own caches, task queues and timers inside `temporal-history` (151 MiB PSS, 12 threads at idle in the core profile). Dev runs one history pod with a handful of workflows, so 4 shards. The count is fixed when the persistence schema is first set up; production must choose its own (512+) before its first install.
### Notary footprint (2026-11-20, release build)
`services/notary`: 1.1 MiB binary, single-threaded tokio runtime. Pod 16/64 Mi like the attestation verifier until measured in-cluster.
### Marquez instead of DataHub (2026-11-20)
Marquez 0.50 API (`-Xmx256m`, SerialGC) 187 MiB PSS + web UI 51 MiB, on the shared Postgres, search off. DataHub would have needed OpenSearch, GMS, frontend and setup jobs (est. 3–5 GB).
### Disk/network pass, first A/B (2026-11-20)
- File refaults per container are ~0 (0.2–0.4 pages/s): idle disk reads are not page-cache thrash from tight memory limits.
- YugabyteDB tserver heartbeat 1 s → 3 s did not change the ~270 KB/s each way between yb-tserver and yb-master (542.9 vs 542.4 KB/s): heartbeats are not the source. Reverted.
- One 15 s window is too noisy for disk I/O of bursty processes (Flink): use the since-start average.
