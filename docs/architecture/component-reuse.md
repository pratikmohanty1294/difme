# Component reuse: what copied-in systems expect vs what DIFME already runs

Owner rule (2026-11-21): when we copy another system in (Hyperswitch, Mifos, Fineract, Mojaloop, ...), check every
infrastructure component it expects and use DIFME's existing one wherever it is compatible, instead of running a
second database, cache, broker, identity provider, secrets store or monitoring stack. Checked against the code and
config of each vendored system (2026-11-21).

Status: **done** = already wired to DIFME's component · **todo** = compatible, to wire · **not used** = the feature is
off, so nothing runs · **kept** = no DIFME equivalent, or the overlap is intended (reason given).

## Hyperswitch (PSP)

| It expects | DIFME component | Status |
|---|---|---|
| PostgreSQL (`pg`) | shared Postgres (`postgres-docdb`, database `hyperswitch`) | done |
| Redis (standalone/cluster) | Valkey (`cache-tier`) | done |
| Kafka (events, drainer) | Strimzi Kafka (`streaming`) | not used: `events.source = logs`; when events are wanted, point `events.kafka.brokers` at our Kafka, not a new broker |
| ClickHouse + OpenSearch (analytics, search) | Postgres via `analytics.source = sqlx`; Trino/RisingWave for our own analytics | not used (both off) |
| Grafana, Loki, Prometheus, Tempo, OTel collector, Vector (its compose stack) | VictoriaMetrics, Loki, Tempo, Alloy (OTLP) | todo: router telemetry to Alloy's OTLP endpoint; nothing of theirs deployed |
| Secrets: AWS/GCP/OCI KMS or HashiCorp Vault (`hashicorp-vault` feature) | OpenBao (Vault API) | todo: build with `hashicorp-vault`, URL = OpenBao; until then secrets come from Kubernetes Secrets |
| File storage: local disk or S3 (`aws_s3`, custom endpoint + path style supported) | SeaweedFS S3 (`lakehouse`) | todo when disputes/files are used (needs the `aws_s3` feature) |
| Superposition (config service) | none needed | not used: falls back to the bundled seed file |
| Locker / key manager / encryption service | none needed | not used (mock locker, key manager off) |
| Email (SES/SMTP), mailhog | none | not used |
| Control center, web SDK, demo app | back-office UI | not used |

## Fineract (CBS)

| It expects | DIFME component | Status |
|---|---|---|
| PostgreSQL or MariaDB | shared Postgres (`fineract_tenants`, `fineract_default`) | done |
| Basic auth with its own users | Keycloak (`security`) — Fineract has OAuth2 resource server and OIDC federation (`FINERACT_SECURITY_OAUTH_ENABLED`, `..._OIDC_FEDERATION_ENABLED`) | todo: Keycloak client for the payment hub; drop the generated basic-auth password afterwards |
| External events via ActiveMQ (JMS) or Kafka | Strimzi Kafka | not used yet; when enabled: Kafka (`FINERACT_EXTERNAL_EVENTS_KAFKA_*`), never ActiveMQ |
| Remote batch workers via JMS/Kafka | Kafka | not used (single node runs manager + worker) |
| Document/image storage on disk or S3 (path style supported) | SeaweedFS S3 | todo: `FINERACT_CONTENT_S3_*` (pod disk is ephemeral today) |
| Actuator / Prometheus metrics | VictoriaMetrics scrape | todo: scrape annotation |
| Its own ledger | TigerBeetle is DIFME's payments ledger | kept: Fineract simulates the banks' core systems; TigerBeetle stays DIFME's own ledger |

## Mifos Payment Hub EE

| It expects | DIFME component | Status |
|---|---|---|
| Zeebe (workflow engine) | Fluxnova (`process`) | done (services/zeebe-on-fluxnova) |
| Zeebe exporters → Kafka/Elasticsearch, Operate, operations app + importer (MySQL) | Fluxnova history API; Loki/Tempo; back-office UI | not used (not vendored) |
| Redis (channel idempotency) | Valkey | done (local runs too) |
| Keycloak / OAuth for channel APIs (`rest.authorization`) | Keycloak | todo: enable with a DIFME realm client |
| Spring Boot 2.6 runtime | Quarkus is DIFME's Java framework | kept: the connectors stay Spring Boot (upstream code); upgraded to Boot 3 for CVEs |
| JWS message signing keys | OpenBao (transit) | not used (`security.jws.enable: false`) |

## Mojaloop (switch + other bank)

| It expects | DIFME component | Status |
|---|---|---|
| MongoDB (Testing Toolkit reports/history) | FerretDB (Mongo API, `forms`) | not used (file storage); if reports are kept, FerretDB — not MongoDB (SSPL) |
| Redis (SDK scheme adapter cache) | Valkey | done (local) |
| Kafka (SDK bulk event handlers) | Strimzi Kafka | not used (handlers off) |
| Keycloak / OAuth (toolkit, SDK OAuth server) | Keycloak | not used |
| SQLite (mojaloop-simulator, in memory) | none needed | kept (in-process, nothing to run) |
| Central switch core (central-ledger etc., MySQL + Kafka) | — | not used (owner chose the Testing Toolkit) |

## Overlaps between DIFME's own components (for the owner)

| Overlap | Today | Proposal |
|---|---|---|
| Two workflow engines: **Temporal** (saga-orchestrator, interim payment hub) and **Fluxnova** (process-gateway, Mifos PH-EE) | both run | owner question: consolidate on one (Fluxnova now carries the payment hub) |
| Interim Temporal payment hub vs Mifos PH-EE | both in the repo | delete the interim hub once the exchange uses PH-EE (owner decision 2026-11-21) |

## Checklist for the next system copied in

For every external service it uses (database, cache, broker, object store, search, identity, secrets, workflow,
observability, mail), record in its `VENDOR.md`: what it expects → DIFME's component → done / todo / not used / kept
(with the reason). Prefer configuration over patches; patch only when no setting exists. Never deploy a second
instance of something DIFME already runs.
