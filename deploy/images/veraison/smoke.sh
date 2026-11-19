#!/usr/bin/env bash
# Starts VTS, verification and provisioning from the built image with the same config the cluster uses.
set -euo pipefail
IMG=$1; W=$(mktemp -d); chmod 777 "$W"
python3 -c "import cryptography, yaml" 2>/dev/null || pip install -q --break-system-packages cryptography pyyaml
python3 - "$W" <<'PY'
import sys, yaml, json, base64
from cryptography.hazmat.primitives.asymmetric import ec
w = sys.argv[1]
docs = list(yaml.safe_load_all(open("deploy/platform/security/attestation/veraison.yaml")))
open(f"{w}/config.yaml", "w").write(docs[0]["data"]["config.yaml"])
k = ec.generate_private_key(ec.SECP256R1()); n = k.private_numbers()
b = lambda i: base64.urlsafe_b64encode(i.to_bytes(32, "big")).rstrip(b"=").decode()
json.dump({"kty": "EC", "crv": "P-256", "alg": "ES256", "d": b(n.private_value), "x": b(n.public_numbers.x), "y": b(n.public_numbers.y)}, open(f"{w}/skey.jwk", "w"))
PY
mkdir -p "$W/keys" "$W/stores" && mv "$W/skey.jwk" "$W/keys/" && chmod -R a+rwX "$W"
run() { docker run -d --name "v-$1" --network host -v "$W/config.yaml:/opt/veraison/config/config.yaml:ro" \
          -v "$W/keys:/opt/veraison/keys:ro" -v "$W/stores:/opt/veraison/stores" "$IMG" "$1-service" --config /opt/veraison/config/config.yaml >/dev/null; }
run vts; sleep 3; run verification; run provisioning; sleep 15
ok=1
for s in vts verification provisioning; do
  st=$(docker inspect -f '{{.State.Running}}' "v-$s"); echo "$s running=$st"; [ "$st" = true ] || { ok=0; docker logs "v-$s" 2>&1 | tail -20; }
done
curl -fsS -o /dev/null -w "verification HTTP %{http_code}\n" http://127.0.0.1:8080/.well-known/veraison/verification || ok=0
curl -fsS -o /dev/null -w "provisioning HTTP %{http_code}\n" http://127.0.0.1:8888/.well-known/veraison/provisioning || ok=0
docker rm -f v-vts v-verification v-provisioning >/dev/null
[ $ok = 1 ] && echo "veraison smoke OK" || { echo "veraison smoke FAILED"; exit 1; }
