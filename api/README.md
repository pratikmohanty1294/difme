# API contracts (PDF §5.10) — contract-first

| Spec | Standard | File | CI gate |
|---|---|---|---|
| Sync REST | OpenAPI 3.1 (ADR-002) | `openapi/difme.openapi.yaml` | Spectral lint + oasdiff breaking-change block |
| gRPC | proto3 + Buf | `proto/difme/**` | `buf lint` + `buf breaking` |
| Async events | AsyncAPI 3.0 | `asyncapi/difme.asyncapi.yaml` | `asyncapi validate` + Apicurio compatibility (BACKWARD_TRANSITIVE) |
| Event envelope | CloudEvents 1.0 | `cloudevents/envelope.schema.json` | Extension attribute validation (traceparent, event_timestamp) |
| Back-office query | GraphQL SDL | `graphql/backoffice.graphql` | graphql-inspector diff |
| Event payloads | Avro | `avro/*.avsc` | Apicurio compatibility check |

Rules: a spec change merges before the code that implements it (the `contract-first` CI check enforces this). Every spec is published to Apicurio by `.github/workflows/api-governance.yml`; clients and servers are generated from the registry artifacts.
