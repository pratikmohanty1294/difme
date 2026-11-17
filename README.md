# DoItForMe (DIFME)

A cloud-native, event-sourced platform built on the **Financial Platform Architecture v1.2** pattern:
CQRS · Event Sourcing · CRDT · Saga · DDD · Formal Verification · Zero-Trust · GitOps.

Everything runs remotely. Open in GitHub Codespaces, then pick the layers you want running:

```bash
make cluster                 # k3d cluster + Argo CD inside the Codespace
make up PROFILE=core         # ingress, security, cache, database, temporal, core services
make up PROFILE=streaming    # Kafka KRaft, Debezium, RisingWave, Flink, Apicurio, KEDA
make status                  # what is running
make down PROFILE=streaming  # free memory for the next layer
```

A free Codespace has up to 4 cores and 16 GB of RAM, so run 1–2 profiles at a time. CI runs every profile in ephemeral clusters.

## Profiles (11 layers)

| # | Layer | Profile | Path |
|---|-------|---------|------|
| 1 | Edge | `edge` (Android emulator) | `edge/` |
| 2 | Hub / Gateway | `hub` | `services/hub/` |
| 3 | Core Platform (CQRS) | `core` | `services/core/` |
| 4 | Workflow Engine | `core` | `deploy/platform/temporal/` |
| 5 | Database Tier | `core` + `data` | `deploy/platform/database-tier/` |
| 6 | Streaming & CDC | `streaming` | `deploy/platform/streaming/` |
| 7 | Deterministic Replay | `streaming` | `services/core/replay-engine/` |
| 8 | Lakehouse & Analytics | `lakehouse` | `deploy/platform/lakehouse/`, `analytics/` |
| 9 | Observability | `observability` | `deploy/platform/observability/` |
| 10 | Cost & FinOps | `observability` | `deploy/platform/opencost/` |
| 11 | Infrastructure | always | `deploy/bootstrap/`, `policies/` |
| — | AI / ML plane | `ml` | `deploy/platform/ml-platform/`, `ml/` |
| — | Back-Office UI | `backoffice` | `backoffice/` |
| — | Forms + business processes (ADR-008) | `process` | `vendor/formio/`, `processes/`, `forms/`, `services/core/process-gateway/` |

## Coverage matrix — every component in the PDF

Strict OSI licensing applies. Where the PDF names a non-OSI or paid component, the swap is listed with its reason (see [docs/architecture/decisions.md](docs/architecture/decisions.md)).

| PDF § | PDF component | DIFME implementation | Path |
|---|---|---|---|
| 4.1 | Rancher / RKE2 / Fleet | k3d (dev) · RKE2-compatible manifests · Rancher Fleet `fleet.yaml` per bundle | `deploy/bootstrap/`, `deploy/fleet/` |
| 4.1 | Rancher Backup Operator → MinIO | Velero + **SeaweedFS** S3 (ADR-005) · RKE2 etcd snapshots every 6h | `deploy/platform/backup/` |
| 4.2 | ingress-system: nginx-ingress, cert-manager, external-dns, metallb | **Envoy Gateway** replaces nginx-ingress (ADR-005); others same | `deploy/platform/ingress-system/` |
| 4.3 | NLB / ALB / QUIC paths, WAF OWASP CRS 4.0 | MetalLB + Envoy Gateway (TLS 1.3) + **Coraza** CRS 4 · UDP 4443 → Quinn via UDPRoute | `deploy/platform/ingress-system/` |
| 4.4 | Istio mTLS STRICT, VirtualService canary, circuit breaker, retries, egress allowlist | **Istio Ambient** (ztunnel + waypoints, ADR-005) with the same policies | `deploy/platform/istio/` |
| 4.5 | Semgrep, Trivy, TLA+/TLC, Sigstore/cosign, SLSA L3, Karate, Newman, k6, Gatling, Locust, Playwright, Cypress, TestCafe, LLM test gen, Argo CD rollback, canary SLO gate, Pyroscope CI gate | Same gates in GitHub Actions + Argo Rollouts; testing consolidated to **Playwright, k6, Schemathesis, jPOS** (ADR-014) | `.github/workflows/`, `tests/` |
| 4.5 | Financial message / scenario simulators | **jPOS** ISO 8583 simulator + scenario runner | `tests/simulator/` |
| 4.5 | Tekton | Tekton pipelines (on-cluster mirror of GitHub Actions) | `deploy/platform/tekton/` |
| 4.6 | Cilium eBPF kube-proxy replacement, Hubble | Same (k3d with flannel disabled) | `deploy/platform/cilium/` |
| 5.1 | Vue 3 + Rust (uniffi) app → **Tauri 2 mobile** (ADR-005), **Wasmtime** WASI P2 components (ADR-016) + ONNX, automerge-rs CRDT, StrongBox/KeyMint/Blake3, HCE HostApduService, WorkManager | Same | `edge/` |
| 5.2 | Quinn hub, session manager, telemetry relay, tonic gRPC bridge, live dashboard relay, rustls+SPIRE, Pyroscope Rust | Same | `services/hub/` |
| 5.3 | quarkus-cmd-svc, qry-svc, saga-orchestrator, consistency-router, rate-limiter, rehydration-svc | Same, on **Java 25 LTS** (ADR-005) | `services/core/` |
| 5.4 | Hibernate 6 + Panache, outbox, MyBatis, Caffeine L1, Redisson JCache L2 | Same (L2 on Valkey) | `services/core/` |
| 5.5 | Event broker (Kafka API) | **Apache Kafka 4 (KRaft)** via Strimzi — OSI replacement | `deploy/platform/streaming/` |
| 5.5 | Debezium (CloudEvents), Flink, Apicurio, Airbyte, KEDA | Same, with **Apache SeaTunnel** instead of Airbyte (ELv2, ADR-009) | `deploy/platform/streaming/` |
| 5.5 | Streaming SQL views | **RisingWave** — OSI replacement | `deploy/platform/streaming/` |
| 5.6 | Iceberg, MinIO, Trino, Spark, StarRocks, dbt, Superset | SeaweedFS + Polaris (ADR-005); Spark covered by Flink + dbt (ADR-015) | `deploy/platform/lakehouse/`, `analytics/` |
| 5.7 | Feast, Weaviate, ONNX Runtime, PostgresML, FlinkML, MLflow | Same except PostgresML (covered by MLflow + ONNX, ADR-015) | `deploy/platform/ml-platform/`, `ml/` |
| 5.8 | SPIFFE/SPIRE, Cilium, OPA Gatekeeper, Kyverno, Sigstore, OpenTelemetry, Alloy, DataHub + OpenLineage | Same, with **Kyverno as the single admission engine** (Gatekeeper and policy-controller removed, ADR-015) | `deploy/platform/security/`, `deploy/platform/governance/` |
| 5.8 | Secrets management | **OpenBao** — OSI replacement | `deploy/platform/security/` |
| 5.8 | Just-in-time access | **Warpgate** — OSI replacement | `deploy/platform/security/` |
| 5.9 | React 18 shell + Module Federation, Settlements (TanStack Query), Compliance (SvelteKit), Analytics (Angular 17 + ECharts), Zustand, Turborepo + pnpm, Tailwind tokens, Storybook, Bundlesize | Same design on **React 19 / Svelte 5 / Angular 20 + Module Federation 2.0 (Rspack)** (ADR-005) | `backoffice/` |
| 5.9 | Kong + APISIX | **Envoy Gateway** routes + JWT (Keycloak) + rate limits (ADR-015) | `deploy/platform/api-gateway/` |
| 5.10 | OpenAPI 4.0 | **OpenAPI 3.1** — 4.0 is unreleased | `api/openapi/` |
| 5.10 | AsyncAPI 3, CloudEvents 1.0, GraphQL SDL, proto3 + Buf, Apicurio, Swagger UI, AsyncAPI Studio | Same | `api/` |
| 6.1 | Temporal OSS | Same — persistence on **YugabyteDB YSQL** | `deploy/platform/temporal/` |
| 6.2 | KEDA lag scaler (100 / 10 / 2–50) | Same, Kafka scaler | `deploy/platform/streaming/keda/` |
| 6.3 | OpenFeature + flagd | Same | `deploy/platform/feature-flags/`, `config/flags/` |
| 8 | TigerBeetle | Same | `deploy/platform/database-tier/tigerbeetle/` |
| 8 | Settlements DB (serializable, multi-region) | **YugabyteDB** (serializable, geo-partitioned tablespaces) — OSI replacement | `deploy/platform/database-tier/yugabytedb/` |
| 8 | YugabyteDB | Same | `deploy/platform/database-tier/yugabytedb/` |
| 8 | Audit log store | **Iceberg Day-0 archive** + YugabyteDB outbox (ADR-015) | `deploy/platform/database-tier/cassandra/` |
| 8 | FoundationDB | **YugabyteDB** serializable unique keys (ADR-015) | `deploy/platform/database-tier/foundationdb/` |
| 8 | L2 cache + CRDT rate limits | **Valkey** (CRDT PN-counter implemented in Lua) — OSI replacement | `deploy/platform/cache-tier/` |
| 8 | Weaviate | Same | `deploy/platform/ml-platform/` |
| 9 | UUIDv7 idempotency at every layer, inbox/outbox | Same | `services/core/common/` |
| 7 / 9 | Deterministic replay + Semgrep purity gate | Same | `services/core/replay-engine/`, `policies/semgrep/` |
| 10 | VictoriaMetrics, Grafana, Loki, Tempo, Pyroscope, Alloy, Alertmanager, vmalert | Same | `deploy/platform/observability/` |
| 10 | Hosted load-test execution | **k6-operator** (self-hosted) — free replacement | `tests/load/` |
| 11.1 | RATS RFC 9334 (TEE, EAT, verifier) | **Veraison** verifier + software-simulated TEE in the emulator | `services/attestation/`, `edge/` |
| 11.2 | Syft SBOM, Harbor, GUAC | Same | `.github/workflows/`, `deploy/platform/harbor/` |
| 13 | Scenarios A–D (on-prem, cloud, hybrid, QUIC proxy chain) | Kustomize overlays + Terraform modules + local QUIC relay | `deploy/overlays/`, `infra/terraform/`, `services/hub/quic-relay/` |
| 14 | `@ConsistencyProfile` routing matrix | Same | `services/core/consistency-router/` |
| 16 | OpenCost cost-per-transaction | Same | `deploy/platform/opencost/` |
| 17 | v2.0 candidates | Tracked, not built | `docs/roadmap.md` |

## Repository layout

```
.devcontainer/   Codespaces environment
api/             OpenAPI, AsyncAPI, proto, GraphQL, CloudEvents contracts
analytics/       dbt, Flink jobs, Spark jobs, Superset
backoffice/      Turborepo micro-frontends
config/          Feature flags, consistency profiles
deploy/          Argo CD apps, Helm values, Kustomize overlays, profiles
docs/            Architecture, decisions, runbooks
edge/            Mobile app, Rust CRDT core, Wasmtime plug-ins, ONNX fraud model
infra/           Terraform modules (cloud scenario B/C)
ml/              Fraud model training → ONNX, Feast definitions
policies/        Kyverno, OPA Rego, Semgrep rules
services/        core (Quarkus), hub (Rust), attestation
specs/tla/       TLA+ specifications, checked in CI
tests/           Playwright (UI + API), k6 (load), Schemathesis (contract), jPOS simulator
```
