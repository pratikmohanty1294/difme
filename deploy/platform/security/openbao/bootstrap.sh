#!/usr/bin/env bash
# One-off configuration after OpenBao is unsealed. Idempotent.
set -euo pipefail
bao secrets enable -path=database database || true
bao write database/config/yugabyte plugin_name=postgresql-database-plugin \
  connection_url="postgresql://{{username}}:{{password}}@yb-tservers.database-tier.svc:5433/difme" \
  allowed_roles="core-cmd,core-qry" username="yugabyte" password="${YB_ROOT_PASSWORD}"
for r in core-cmd core-qry; do
  bao write database/roles/$r db_name=yugabyte default_ttl=1h max_ttl=4h \
    creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT difme_${r//-/_} TO \"{{name}}\";"
done
bao secrets enable pki || true
bao secrets tune -max-lease-ttl=87600h pki
bao write pki/root/generate/internal common_name="DIFME Root CA" ttl=87600h
bao secrets enable -path=pki_int pki || true                   # intermediate CA for SPIRE upstream authority
bao secrets enable transit || true
bao write -f transit/keys/pii type=aes256-gcm96                # AES-256 field encryption (PDF §11.3 data layer)
bao auth enable kubernetes || true
bao write auth/kubernetes/config kubernetes_host="https://kubernetes.default.svc"
