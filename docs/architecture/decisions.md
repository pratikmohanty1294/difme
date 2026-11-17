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

## ADR-008 — Dynamic forms and business-process engine (owner-approved 2026-11-17)
These are additions to the PDF requested by the owner.

| Concern | Decision | Licence | Notes |
|---|---|---|---|
| Dynamic forms with user-defined metadata | **formio** open-source server **v4.11.0** + **formio.js v5.7.0** builder/renderer, **vendored** into `vendor/formio/` at pinned tags | OSL-3.0 (server), MIT (js) | The hosted SaaS was rejected (paid after trial). The source lives in this repo, is built into our own signed images, and is updated with `ci/vendor-sync.sh`. OSL-3.0 requires publishing modifications when deployed, which this public repo satisfies. |
| formio database | **FerretDB 2.7** on **PostgreSQL + DocumentDB extension** | Apache-2.0 / PostgreSQL / MIT | MongoDB Community is SSPL (not OSI). FerretDB speaks the MongoDB wire protocol. formio's own test suite runs against it in CI to catch gaps. |
| Business processes, human tasks, business rules | **Fluxnova 3.1.1** (FINOS fork of the Camunda 7 CE engine): BPMN 2.0, DMN, CMMN 1.1 | Apache-2.0 | CMMN is deprecated upstream and kept for backward compatibility only. Persistence is on PostgreSQL (officially supported). |
| Responsibility split (owner design) | **Fluxnova** owns business configuration and rules as DMN (limits, thresholds, eligibility, routing), **form-field constraints and dependencies** (required/visible/allowed values/ranges, cross-field rules) and human-centric BPMN. **Temporal** owns all orchestration between processes and services. Technical config and kill switches stay in flagd and GitOps. | — | Each formio form's metadata pins `decisionKey` + `decisionVersion`. A `form-rules` endpoint evaluates the DMN (debounced, cached by input hash) while the user types, and the same DMN is re-evaluated authoritatively on submit. |
| Temporal vs Fluxnova | **Both, split by purpose.** Temporal runs system sagas (payments, transfers; TLA+-verified). Fluxnova runs business processes: approvals, KYC/onboarding, disputes, and DMN rules editable by operations. | — | A Fluxnova external-task worker (`services/process/temporal-bridge`) can start Temporal workflows. |
| Process UI | **Process Studio** back-office micro-frontend: in-browser BPMN/DMN modelling with the **Apache KIE standalone editors**, a Fluxnova properties panel (assignee, formKey, decisionRef, external-task topic), deploy to engine, task inbox rendering formio forms, process monitoring. Fluxnova Cockpit/Tasklist/Admin are deployed for operations. | Apache-2.0 | bpmn-js/dmn-js were rejected because their licence requires a visible watermark (rule: open source with no limitations). |

## ADR-009 — Rule: open source and free with no limitations (owner, 2026-11-17)
Every component must be OSI-approved **and** free to use with no limitations: no watermark or attribution-display duties, no BSL/SSPL/ELv2/source-available licences, no usage-capped free tiers or paid feature gates. Copyleft (GPL/AGPL/OSL/MPL) is allowed; it imposes sharing obligations, not usage limits.

Applying the rule:
| Component | Problem | Replacement |
|---|---|---|
| bpmn-js / dmn-js | Watermark required | Apache KIE BPMN/DMN standalone editors (Apache-2.0) |
| Airbyte (PDF §5.5) | Platform under Elastic License v2 | **Apache SeaTunnel** (Apache-2.0): 100+ connectors incl. CDC, Zeta engine or Flink, web UI |

Unavoidable platform quota: GitHub Codespaces' free hours (Actions is unlimited for public repositories). No free hosting removes this.

## ADR-010 — Back-office stack and identity (owner-approved 2026-11-17)
| Concern | Decision | Licence |
|---|---|---|
| Identity provider (OIDC/SAML, LDAP federation, RBAC — PDF §4.1 "LDAP/OIDC") | **Keycloak 26.8.2**, realm `difme` with groups compliance / settlements / analytics / ops / process-designers | Apache-2.0 |
| Shell + React remotes | React 19.3, Module Federation 2.9 on Rsbuild/Rspack, TanStack Query, Zustand, Tailwind CSS 4 tokens | MIT |
| Compliance remote | **Svelte 5** exposed through Module Federation as a `mount(el, props)` function. SvelteKit's SSR router is not used inside a federated remote. | MIT |
| Analytics remote | **Angular 22** (latest; the plan said 20) + ECharts 6, shipped as an Angular Elements web component `<difme-analytics>` loaded by the shell's remote registry. Angular's builder is not Rspack, so a framework-neutral custom element is the integration contract. | MIT / Apache-2.0 |
| Process Studio / form builder | Apache KIE BPMN/DMN standalone editors 10.2 (lazy-loaded); vendored formio.js builder/renderer | Apache-2.0 / MIT |
| Auth in browser | oidc-client-ts (Authorization Code + PKCE); the token is shared with remotes through the singleton `@difme/auth` store | Apache-2.0 |
| Feature flags in browser | OpenFeature web SDK + OFREP provider → flagd | Apache-2.0 |

Performance budget (PDF §5.9): each remote's **initial** JS must be ≤ 200 KB compressed (CI gate). The KIE editors and the formio builder are larger, so they load lazily on first use and are excluded from the initial budget.

## ADR-011 — Bulk file processing (owner-approved 2026-11-17)
**Requirement:** CSV, flat (fixed-width), JSON and Excel files of about 10M records, fully processed in **≤ 5 minutes** (≈ 33k records/s sustained, end to end). Every record is validated against linked records in the database and against DMN business rules.

**Decision:** an **Apache Flink batch job** (operator already deployed) with:
- **Parallel reading.** File splits for CSV/JSON-lines/fixed-width; every record id is `sha256(file):byteOffset`. Excel goes through Apache POI's streaming reader (`ExcelToCsv`, constant memory) to CSV first, because Excel can't be split.
- **Hybrid DB validation.**
  - Static reference data (account master, products, limits) is snapshotted into keyed in-memory state at job start, with one partitioned bulk scan.
  - Volatile checks (balances, duplicates, status) are **batched async lookups** (≈1,000 keys per `IN` query) against YugabyteDB follower replicas. There is never one query per record.
- **Rules.** The Fluxnova DMN engine is embedded in the operators. The pinned decision version is compiled once per job and evaluated in-process (µs per record), using the same DMN as the forms (ADR-008).
- **Idempotency.** Record ids are derived from the file SHA-256 plus the line number (UUIDv7-compatible), so re-running a file never double-applies.
- **Outputs.**
  - Valid records → batched sinks: TigerBeetle (≤ 8,190 per batch), Kafka or Iceberg.
  - Rejected records → error file with line number + reasons.
  - Run report → back-office.

**Throughput caveat:** writing 33k rows/s *into YugabyteDB* needs a multi-node cluster. The single-node Codespace cluster can't sustain it. The 5-minute target is measured by a CI benchmark on a generated 10M-record file, and the measured figure is published rather than assumed.

## ADR-012 — Efficiency rule and service runtimes (owner-approved 2026-11-17)
**Rule:** everything must be extremely lightweight (low RAM/CPU/I/O) even with the whole platform running, while keeping maximum throughput and minimum latency. Numbers are measured in CI, not assumed.

**Runtimes (hybrid):**
| Services | Runtime | Why |
|---|---|---|
| rate-limiter, consistency-router, projection-svc, replay-engine | **Native image** (Mandrel, GPLv2+CE) | ~25–50 MB RSS, ~50 ms start; these are I/O-bound, so JIT peak speed matters little |
| cmd-svc, qry-svc, saga-orchestrator, rehydration-svc, process-gateway | **JVM 25 + AOT cache** (JEP 483/514/515), compact object headers, tuned GC | Hot paths keep JIT peak throughput; JNI clients (TigerBeetle, FoundationDB) and Temporal are simpler on the JVM; the AOT cache removes most warm-up |
Each service's RSS, startup and throughput are recorded by CI and compared against the previous build.

**Measured (CI, 2026-11-17):** native images built for rate-limiter, consistency-router, projection-svc and replay-engine. process-gateway moved to JVM + AOT cache: the Temporal SDK's shaded gRPC/Netty fails native-image analysis (`ApplicationProtocolConfig` in the image heap). Revisit once the SDK ships native-image metadata.
Bulk benchmark (ADR-011), 10M records with DB + DMN validation on a 4-vCPU runner: **855 s → 56 s (178,571 records/s)** after the JVM-shared account snapshot, memoised DMN, flat POJO records and per-partition counting.

## ADR-013 — Cilium data path in the Codespace (verified live 2026-11-17)
Inside k3d (Kubernetes-in-Docker on a Codespace), Cilium's full kube-proxy replacement left host-network pods unable to reach ClusterIP services, even with legacy host routing and cgroup-root changes. The node-agent namespaces depend on that path.
**Decision:** the Codespace cluster runs Cilium **alongside k3s kube-proxy**. eBPF networking, network policy (L3–L7), WireGuard and Hubble stay. Real clusters (RKE2 on VMs or bare metal, `deploy/fleet/rke2-config.yaml` with `disable-kube-proxy`) use `kubeProxyReplacement: true` for the full eBPF service data path (PDF §4.6), where it is supported natively.
