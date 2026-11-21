#!/usr/bin/env python3
"""Verifies a signed DIFME orders report (JSON from GET /v1/exchange/reports/orders?format=json), for the recipient of
a report file (a regulator, a partner, an auditor):

  1. SHA-256 of the compact JSON of `rows` equals meta.rowsSha256 (the rows weren't changed);
  2. meta.signatureEd25519 is the exchange operator's signature over that digest, checked with the public key named
     meta.signedBy, as published by the exchange at GET /v1/exchange/keys (or a saved copy of that JSON).

  pip install cryptography
  python3 verify-report.py difme-orders-all-2026-11-21.json https://<exchange>/v1/exchange/keys
  python3 verify-report.py difme-orders-all-2026-11-21.json keys.json
"""
import base64, hashlib, json, sys, urllib.request

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.serialization import load_der_public_key


def main(report_path: str, keys_src: str) -> int:
    with open(report_path, encoding="utf-8") as f:
        doc = json.load(f)
    meta, rows = doc["meta"], doc["rows"]
    # Same bytes the exchange hashed: compact separators, UTF-8, key order as in the file.
    digest = hashlib.sha256(json.dumps(rows, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).digest()
    if digest.hex() != meta["rowsSha256"]:
        print(f"FAIL rows were changed: SHA-256 {digest.hex()} != {meta['rowsSha256']}")
        return 1
    if keys_src.startswith(("http://", "https://")):
        with urllib.request.urlopen(keys_src, timeout=20) as r:
            keys = json.load(r)
    else:
        with open(keys_src, encoding="utf-8") as f:
            keys = json.load(f)
    key_b64 = keys.get(meta["signedBy"])
    if not key_b64:
        print(f"FAIL no published key named {meta['signedBy']}")
        return 1
    try:
        load_der_public_key(base64.b64decode(key_b64)).verify(base64.b64decode(meta["signatureEd25519"]), digest)
    except InvalidSignature:
        print(f"FAIL the signature does not verify with {meta['signedBy']}'s published key")
        return 1
    print(f"ok   {meta['rows']} rows, {meta['from']}..{meta['to']} ({meta['pack']}), signed by {meta['signedBy']}, SHA-256 {meta['rowsSha256']}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
