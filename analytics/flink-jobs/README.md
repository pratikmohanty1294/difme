# DIFME Flink jobs

| Job | Class | Mode | Purpose |
|---|---|---|---|
| Bulk file validation | `dev.difme.flink.bulk.BulkFileJob` | BATCH | ADR-011: 10M-record CSV/fixed-width/JSON (Excel via `ExcelToCsv`) with batched DB checks and embedded DMN |
| Fraud CEP | `dev.difme.flink.fraud.FraudCepJob` | STREAMING | PDF §7.4 L3 velocity / amount patterns → `fraud.alerts.v1` |
| Event archive | `dev.difme.flink.archive.EventArchiveJob` | STREAMING | Day-0 history: payments and fraud alerts → Iceberg (Polaris + SeaweedFS) |

Bulk run (cluster): `deploy/platform/streaming/flink-jobs/bulk-template.yaml`. Benchmark: `.github/workflows/bulk-benchmark.yml`.
