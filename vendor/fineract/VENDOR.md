# Apache Fineract (vendored)

Core banking (CBS) simulator for DIFME (owner decision 2026-11-21). Upstream https://github.com/apache/fineract, tag
`1.16.1`, commit in `vendor/VENDORED.json`. Licence: Apache-2.0 (see `LICENSE_SOURCE`/`NOTICE` in this tree).

Not committed on purpose:
- `fineract-provider/src/main/resources/keystore.jks`: upstream's development TLS keystore (it holds a private key).
  DIFME runs Fineract with `server.ssl.enabled=false`; traffic between pods is encrypted by the Istio ambient mesh.

Local changes: none yet. Any change is listed here with its reason.
