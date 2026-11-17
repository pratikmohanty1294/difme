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
| Event broker (Kafka API) | Non-OSI licence | Apache Kafka 4 (KRaft) via Strimzi | Apache-2.0 | No ZooKeeper. Tiered storage: see ADR-006. Wasm transforms → Kafka Streams / Flink. |
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

## ADR-005 — Newer free alternatives (owner-approved 2026-11-17)
The owner chose each of these when offered a newer free alternative to the PDF component.

| PDF component | Chosen | Reason |
|---|---|---|
| NGINX Ingress Controller + ModSecurity | **Envoy Gateway** (Gateway API) + **Coraza** WAF running OWASP CRS 4 | ingress-nginx was retired upstream (no fixes after March 2026). Gateway API is the Kubernetes standard and routes UDP/QUIC and HTTP/3 natively. |
| MinIO | **SeaweedFS** | MinIO community edition stopped shipping binaries and images and is in maintenance mode. SeaweedFS is Apache-2.0, S3-compatible, has erasure coding and replication, and is light enough for a Codespace. |
| Istio with sidecars | **Istio Ambient** (ztunnel + waypoints) | GA since 1.24 and much lighter (PDF §17 candidate). mTLS STRICT, L7 policy, canary and circuit breaking keep working through waypoints. |
| Java 21 | **Java 25 LTS** | Current LTS. Fixes virtual-thread pinning on `synchronized`, adds scoped values and compact object headers. |
| React 18 / Svelte 4 / Angular 17 + Webpack 5 Module Federation | **React 19 / Svelte 5 / Angular 20 + Module Federation 2.0 on Rspack** | The old majors are out of support. MF 2.0 adds manifest-based remotes and type hints, and Rspack builds faster. |
| Native Android shell + uniffi | **Tauri 2 mobile** (Vue 3 UI + Rust core; Kotlin plugins for HCE/NFC/KeyStore) | One Rust codebase shared with the hub, first-class Android and iOS. |
| Iceberg catalog (unspecified) | **Apache Polaris** (Iceberg REST) | One catalog with RBAC and credential vending for Trino, Spark, Flink and StarRocks. |
| Kong + APISIX | **Kept as in the PDF** | Owner's choice: Kong external, APISIX back-office, both behind Envoy Gateway. |

## ADR-006 — Kafka tiered storage (decided 2026-11-17)
The PDF tiers old broker segments to object storage. Apache Kafka has the KIP-405 interface but no built-in S3 RemoteStorageManager; the available Apache-2.0 plugins are published by vendors.
**Current state:** event topics keep infinite local retention, and Flink archives every event to Iceberg on SeaweedFS (Day-0 history). **Decision (owner):** no KIP-405 plugin. Infinite retention plus the Flink→Iceberg archive is the tiering mechanism, which avoids vendor-named plugins.

## ADR-007 — Backups (decided 2026-11-17)
**Decision (owner):** keep Velero for cluster backup to SeaweedFS S3. The vendor word in its S3 plugin is accepted as a technical identifier.
