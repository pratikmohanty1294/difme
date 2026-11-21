# Juspay Hyperswitch (vendored)

PSP (PA/PG) simulator for DIFME (owner decision 2026-11-21). Upstream https://github.com/juspay/hyperswitch, tag
`v1.129.0`, commit in `vendor/VENDORED.json`. Licence: Apache-2.0 (`LICENSE`).

Left out (not part of the Cargo workspace): `docs/`, `postman/`, `cypress-tests/`, `api-reference/`.

Local changes: none yet. Any change is listed here with its reason.

How DIFME runs it (no change to this tree):
- Image: `deploy/images/hyperswitch/Dockerfile` builds `router` with the default features (v1 + dummy connector).
- Config: the upstream `config/docker_compose.toml`, with DIFME's hosts and secrets as `ROUTER__*` environment
  overrides (`deploy/platform/rails/hyperswitch/hyperswitch.yaml`).
- Schema: `deploy/images/hyperswitch/migrate.sh` applies `migrations/` with psql and records versions in diesel's
  `__diesel_schema_migrations`, so diesel_cli could take over unchanged.
