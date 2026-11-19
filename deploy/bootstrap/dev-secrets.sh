#!/usr/bin/env bash
# Creates every Secret the manifests reference, with random values, for a Codespace/dev cluster.
# Idempotent: existing secrets are never overwritten. Production clusters get these from OpenBao instead.
set -euo pipefail

rand() { head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c "${1:-24}"; }
ns() { kubectl create namespace "$1" --dry-run=client -o yaml | kubectl apply -f - >/dev/null; }
secret() {  # secret <namespace> <name> key=value...
  local n=$1 name=$2; shift 2; ns "$n"
  if kubectl -n "$n" get secret "$name" >/dev/null 2>&1; then echo "= $n/$name exists"; return; fi
  local args=(); for kv in "$@"; do args+=(--from-literal="$kv"); done
  kubectl -n "$n" create secret generic "$name" "${args[@]}" >/dev/null && echo "+ $n/$name"
}

# Shared credentials, generated once and replicated where several namespaces need them.
PG_DOCDB=$(rand); FLUX_ADMIN=$(rand); FORMIO_KEY=$(rand 32); S3_KEY=$(rand 20); S3_SECRET=$(rand 40); YB=$(rand)

secret database-tier postgres-docdb password="$PG_DOCDB"
secret process       postgres-docdb password="$PG_DOCDB"
secret forms         ferretdb postgresql-url="postgres://difme:${PG_DOCDB}@postgres-docdb.database-tier.svc:5432/postgres"
secret forms         formio api-key="$FORMIO_KEY" api-keys="$FORMIO_KEY" jwt-secret="$(rand 48)" \
                     mongo-url="mongodb://ferretdb.forms.svc:27017/formio" root-email="admin@difme.local" root-password="$(rand)"
secret process       formio api-key="$FORMIO_KEY"
secret process       fluxnova admin-password="$FLUX_ADMIN" admin-basic="admin:${FLUX_ADMIN}"

cat > /tmp/s3.json <<EOF
{"identities":[{"name":"difme","credentials":[{"accessKey":"${S3_KEY}","secretKey":"${S3_SECRET}"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
EOF
ns lakehouse
kubectl -n lakehouse get secret seaweedfs-s3-config >/dev/null 2>&1 || kubectl -n lakehouse create secret generic seaweedfs-s3-config --from-file=s3.json=/tmp/s3.json >/dev/null
rm -f /tmp/s3.json
secret lakehouse     seaweedfs-s3-admin RCLONE_CONFIG_SW_TYPE=s3 RCLONE_CONFIG_SW_PROVIDER=SeaweedFS \
                     RCLONE_CONFIG_SW_ENDPOINT=http://seaweedfs-s3:8333 RCLONE_CONFIG_SW_ACCESS_KEY_ID="$S3_KEY" RCLONE_CONFIG_SW_SECRET_ACCESS_KEY="$S3_SECRET"
secret lakehouse     polaris-bootstrap credentials="POLARIS,root,$(rand)"
secret streaming     risingwave-s3 AWS_ACCESS_KEY_ID="$S3_KEY" AWS_SECRET_ACCESS_KEY="$S3_SECRET"   # key names the chart requires
# RisingWave meta store is on the shared Postgres (ADR-019 pattern); both copies must match, so reuse an existing one.
RW_PG=$(kubectl -n streaming get secret risingwave-db -o jsonpath='{.data.password}' 2>/dev/null | base64 -d || true); RW_PG=${RW_PG:-$(rand)}
secret streaming     risingwave-db username=risingwave password="$RW_PG"
secret database-tier risingwave-db password="$RW_PG"
secret streaming     debezium-db password="$YB"
secret ml-platform   mlflow-s3 AWS_ACCESS_KEY_ID="$S3_KEY" AWS_SECRET_ACCESS_KEY="$S3_SECRET"
secret ml-platform   mlflow-db username=mlflow password="$YB"
secret ml-platform   postgresml-db password="$(rand)"
secret backup        velero-s3 cloud="$(printf '[default]\naws_access_key_id=%s\naws_secret_access_key=%s\n' "$S3_KEY" "$S3_SECRET")"
# Temporal persists on the shared platform Postgres (ADR-019). Reuse an existing password so both copies match.
TEMPORAL_PG=$(kubectl -n temporal get secret temporal-db -o jsonpath='{.data.password}' 2>/dev/null | base64 -d || true)
TEMPORAL_PG=${TEMPORAL_PG:-$(rand)}
secret temporal      temporal-db password="$TEMPORAL_PG"
secret database-tier temporal-db password="$TEMPORAL_PG"
secret harbor        harbor-db password="$YB"
secret governance    datahub-db password="$YB"
secret observability grafana-admin admin-user=admin admin-password="$(rand)"
secret security      keycloak admin-password="$(rand)"
secret security      warpgate admin-password="$(rand)"
# Veraison EAR signing key: EC P-256 as a JWK (Veraison's loader only reads JWK). b64u: hex -> base64url.
b64u() { xxd -r -p | base64 -w0 | tr '+/' '-_' | tr -d '='; }
ear_jwk() {
  local t; t=$(openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null | openssl ec -text -noout 2>/dev/null)
  local d pub; d=$(echo "$t" | awk '/priv:/{f=1;next} /pub:/{f=0} f' | tr -d ' :
')
  pub=$(echo "$t" | awk '/pub:/{f=1;next} /ASN1 OID/{f=0} f' | tr -d ' :
'); pub=${pub#04}
  printf '{"kty":"EC","crv":"P-256","alg":"ES256","d":"%s","x":"%s","y":"%s"}'     "$(printf %s "${d: -64}" | b64u)" "$(printf %s "${pub:0:64}" | b64u)" "$(printf %s "${pub:64:64}" | b64u)"
}
kubectl -n security get secret veraison-ear >/dev/null 2>&1 || secret security veraison-ear skey.jwk="$(ear_jwk)"
secret security      postgres-docdb password="$PG_DOCDB"
POLARIS_CRED="root:$(rand)"
for n in streaming lakehouse; do
  secret $n polaris-client credential="$POLARIS_CRED"
  secret $n flink-s3 access-key="$S3_KEY" secret-key="$S3_SECRET"
done
secret streaming     bulk-db password="$YB"
echo "Dev secrets ready. Read one with: kubectl -n <ns> get secret <name> -o jsonpath='{.data.<key>}' | base64 -d"
