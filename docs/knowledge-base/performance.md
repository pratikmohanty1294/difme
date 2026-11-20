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
