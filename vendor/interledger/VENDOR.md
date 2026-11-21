# Interledger java-ilp-core (vendored)

Upstream https://github.com/interledger/java-ilp-core at the commit before 0.7.0 (version 0.6.1-SNAPSHOT, the API
PH-EE's Mojaloop connector was written against; no release tags, pinned by commit in `vendor/VENDORED.json`).
Licence: Apache-2.0. Build: `mvn install -DskipTests` (Java 8 source level).

Why: PH-EE's Mojaloop connector used it through org.mifos:interop-ilp-conditions, which bundles this jar but is itself
under "All Rights Reserved ... use is prohibited without permission" — not open source. DIFME uses this Apache-2.0
source instead, plus `services/fspiop-ilp-models` (the FSPIOP Transaction model written from the Mojaloop API
specification) in place of interop-ilp-conditions.
