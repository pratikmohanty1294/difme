# Interledger java-ilp-core (vendored)

Upstream https://github.com/interledger/java-ilp-core at the commit before 0.7.0 (version 0.6.1-SNAPSHOT, the API
PH-EE's Mojaloop connector was written against; no release tags, pinned by commit in `vendor/VENDORED.json`).
Licence: Apache-2.0. Build: `mvn install -DskipTests` (Java 8 source level).

Why: PH-EE's Mojaloop connector used it through org.mifos:interop-ilp-conditions, which bundles this jar but is itself
under "All Rights Reserved ... use is prohibited without permission" — not open source. DIFME uses this Apache-2.0
source instead, plus `services/fspiop-ilp-models` (the FSPIOP Transaction model written from the Mojaloop API
specification) in place of interop-ilp-conditions.

Local change (`vendor/patches/java-ilp-core/0001-ilp-v1-envelope-as-mojaloop.patch`): this 2017 snapshot wrote ILP v1
payment packets without the length-prefixed envelope and the trailing extensions byte that the final format (and
Mojaloop's ilp-packet-v1) uses, so it could not read a real Mojaloop bank's packets nor be read by one. Patched to read
and write the final format; verified byte-for-byte identical to Mojaloop's encoder and decoding Mojaloop packets.
