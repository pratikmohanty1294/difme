#!/bin/sh
# The SQL policy store needs its table before VTS starts (Veraison's own deployments run a separate init step).
set -eu
umask 0002
mkdir -p /opt/veraison/stores/vts
db=/opt/veraison/stores/vts/po-store.sql
[ "${1:-}" = vts-service ] && sqlite3 "$db" 'CREATE TABLE IF NOT EXISTS kvstore (kv_key text NOT NULL, kv_val text NOT NULL);'
exec "$@"
