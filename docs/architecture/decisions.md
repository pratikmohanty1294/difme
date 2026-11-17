# Architecture Decision Records

DIFME follows Financial Platform Architecture v1.2. These records log every deviation from the PDF and the reason for it.

## ADR-001 — Strict OSI licensing
**Decision:** Every runtime component must carry an OSI-approved licence. The PDF claims "100% OSI", but several of the components it names have since changed licence.

| PDF role | Issue | Replacement | Licence | Notes |
|---|---|---|---|---|
| L2 cache + CRDT rate limits | Non-OSI licence | Valkey 8 | BSD-3 | PDF §8 already evaluates Valkey. The PN-Counter CRDT for rate limiting is a Lua script plus per-region counters (`services/core/rate-limiter`). |
| Secrets management | Non-OSI licence | OpenBao 2 | MPL-2.0 | Linux Foundation project. Dynamic DB creds TTL=1h, PKI, transit. |
| Just-in-time infra access | Non-OSI licence | Warpgate | Apache-2.0 | Identity-aware SSH/HTTP/DB bastion with session recording. |
| Streaming SQL views | Non-OSI licence | RisingWave | Apache-2.0 | Postgres-wire streaming SQL with incremental materialized views, Kafka source and sink. |
| Event broker (Kafka API) | Non-OSI licence | Apache Kafka 4 (KRaft) via Strimzi | Apache-2.0 | No ZooKeeper. Tiered storage → MinIO (KIP-405). Wasm transforms → Kafka Streams / Flink. |
| Settlements DB (serializable, multi-region) | Non-OSI licence | YugabyteDB 2.25 YSQL | Apache-2.0 | Serializable isolation, geo-partitioned tablespaces, Raft. Also the Temporal persistence backend. |
| Audit log store | Non-OSI licence | Apache Cassandra 5 | Apache-2.0 | Append-only audit log, quorum writes, SAI indexes. |

## ADR-002 — Non-existent or proprietary items
| PDF item | Issue | Replacement |
|---|---|---|
| OpenAPI 4.0 | Not released (the "Moonwalk" draft) | OpenAPI 3.1, with a CI gate (oasdiff) that blocks breaking changes |
| Financial message / scenario simulators | Not publicly available | jPOS (AGPL-3.0, OSI) ISO 8583 host + client simulator, plus a Python scenario runner |
| Hosted load-test execution | Paid SaaS | k6-operator (Apache-2.0) runs distributed tests in-cluster; results go via remote-write to VictoriaMetrics |
| Hardware TEE | Hardware needed | The Android emulator has no hardware-backed keystore, so a software-simulated TEE signs EAT tokens; the RATS verifier is Veraison (Apache-2.0) |
| Three active regions | Paid cloud | Three k3d clusters (or three namespaces with zone labels) simulate the regions; provider-neutral Terraform modules are in `infra/terraform/` |

## ADR-003 — Runtime environment
**Decision:** GitHub Codespaces (4 cores / 16 GB) is the only runtime; there is no always-on host. The platform is split into profiles that can be switched on and off. CI (GitHub Actions, which is free for public repos) runs each profile's integration tests in ephemeral k3d clusters.
**Consequence:** Throughput and latency SLAs in PDF §1.1 and §12 are design targets. They are measured by k6 in CI on shared runners, so absolute numbers will be lower than on production hardware.

## ADR-004 — Repo visibility
**Decision:** The repository is public. The PDF's "CONFIDENTIAL" marking is not carried into the repo. Secrets live only in GitHub Actions secrets, Codespaces secrets and OpenBao.
