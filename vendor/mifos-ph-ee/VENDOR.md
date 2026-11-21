# Mifos Payment Hub EE (vendored)

DIFME's payment hub (owner decision 2026-11-21): Mifos Payment Hub EE, with **Fluxnova in place of Zeebe** (Zeebe's
broker is under the Camunda License 1.0, not OSI). Licence: MPL-2.0 (`LICENSE` in each directory). Pinned versions and
commits are in `vendor/VENDORED.json`.

| Directory | Upstream | What it is |
|---|---|---|
| `connector-common` | openMF/ph-ee-connector-common v1.9.1 | Shared DTOs, Camel helpers, error mapping |
| `connector-channel` | openMF/ph-ee-connector-channel v1.9.0 | The channel API: starts payment flows, answers channels |
| `connector-ams-mifos` | openMF/ph-ee-connector-ams-mifos v1.7.0 | Fineract (AMS) connector: block / book / release funds through Fineract's interoperation API |
| `flows` | openMF/ph-ee-env-labs, `orchestration/feel` at a pinned commit (no release tags) | The BPMN payment flows |

Not committed on purpose: upstream's development TLS keystores (`keystore.jks` in both connectors; they hold private
keys). `ci/vendor-sync.sh` drops any `*.jks`, `*.p12`, `*.pfx`, `*.key`. DIFME runs the connectors with
`server.ssl.enabled=false`; pod-to-pod traffic is encrypted by the Istio ambient mesh.

Building (no source changes needed): JDK 17 (as upstream), Gradle wrapper 7.3, with
`deploy/images/mifos-ph-ee/vendored-common.gradle` as an init script. It builds the connectors against the vendored
`connector-common` (published to mavenLocal first: `./gradlew publishToMavenLocal -x test` in `connector-common`)
instead of the SNAPSHOTs upstream pulls from a third-party Artifactory, and builds `connector-ams-mifos` for Java 17
(upstream says 13; the shared library needs 17).

Local changes: none yet. Each change for the Fluxnova port is listed here with its reason.
