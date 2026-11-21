#!/usr/bin/env bash
# Applies Hyperswitch's v1 migrations (vendor/hyperswitch/migrations) with psql, the way diesel_cli does:
# in name order, each up.sql once, recorded in __diesel_schema_migrations (so diesel_cli can take over later).
# A migration whose metadata.toml says run_in_transaction = false runs outside a transaction
# (CREATE INDEX CONCURRENTLY and friends); the rest run in one transaction together with their record.
# Env: DATABASE_URL (postgres://user:pass@host:port/db), MIGRATIONS_DIR (default /migrations).
set -euo pipefail
dir=${MIGRATIONS_DIR:-/migrations}
psql() { command psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -qAtX "$@"; }

psql -c "CREATE TABLE IF NOT EXISTS __diesel_schema_migrations (
  version VARCHAR(50) PRIMARY KEY NOT NULL, run_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"
done_list=$(psql -c "SELECT version FROM __diesel_schema_migrations")

applied=0
for m in $(ls "$dir" | sort); do
  [ -f "$dir/$m/up.sql" ] || continue
  version=$(echo "${m%%_*}" | tr -d '-')          # 2022-09-29-084920_x -> 20220929084920 (diesel's rule)
  grep -qx "$version" <<<"$done_list" && continue
  if grep -qs 'run_in_transaction *= *false' "$dir/$m/metadata.toml"; then
    psql -f "$dir/$m/up.sql"
    psql -c "INSERT INTO __diesel_schema_migrations (version) VALUES ('$version')"
  else
    { echo "BEGIN;"; cat "$dir/$m/up.sql"; echo ";"
      echo "INSERT INTO __diesel_schema_migrations (version) VALUES ('$version');"; echo "COMMIT;"; } | psql
  fi
  applied=$((applied + 1))
done
echo "hyperswitch migrations: $applied applied, $(psql -c 'SELECT count(*) FROM __diesel_schema_migrations') recorded"
