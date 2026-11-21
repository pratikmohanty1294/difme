# Mifos Payment Hub EE (vendored)

DIFME's payment hub (owner decision 2026-11-21): Mifos Payment Hub EE, with **Fluxnova in place of Zeebe** (Zeebe's
broker is under the Camunda License 1.0, not OSI). Licence: MPL-2.0 (`LICENSE` in each directory). Pinned versions and
commits are in `vendor/VENDORED.json`.

| Directory | Upstream | What it is |
|---|---|---|
| `connector-common` | openMF/ph-ee-connector-common v1.9.1 | Shared DTOs, Camel helpers, error mapping |
| `connector-channel` | openMF/ph-ee-connector-channel v1.9.0 | The channel API: starts payment flows, answers channels |
| `connector-ams-mifos` | openMF/ph-ee-connector-ams-mifos v1.7.0 | Fineract (AMS) connector: block / book / release funds through Fineract's interoperation API |
| `connector-mojaloop` | openMF/ph-ee-connector-mojaloop-java v1.5.2 | Mojaloop (FSPIOP) connector: party lookup, quotes, transfers through the central switch |
| `flows` | openMF/ph-ee-env-labs, `orchestration/feel` at a pinned commit (no release tags) | The BPMN payment flows |

Not committed on purpose: upstream's development TLS keystores (`keystore.jks` in both connectors; they hold private
keys). `ci/vendor-sync.sh` drops any `*.jks`, `*.p12`, `*.pfx`, `*.key`. DIFME runs the connectors with
`server.ssl.enabled=false`; pod-to-pod traffic is encrypted by the Istio ambient mesh.

Building (no source changes needed): JDK 17 (as upstream), Gradle wrapper 7.3, with
`deploy/images/mifos-ph-ee/vendored-common.gradle` as an init script. It builds the connectors against the vendored
`connector-common` (published to mavenLocal first: `./gradlew publishToMavenLocal -x test` in `connector-common`)
instead of the SNAPSHOTs upstream pulls from a third-party Artifactory, and builds `connector-ams-mifos` for Java 17
(upstream says 13; the shared library needs 17).

Local changes to the Mifos source: two patches to connector-mojaloop (`vendor/patches/ph-ee-mojaloop/`, re-applied by
`ci/vendor-sync.sh`), both making PH-EE talk to a real Mojaloop bank rather than to a second tenant of the same hub:
- `0001` quote: the payee's FSP is the one the party lookup found; upstream looked the payee FSP up as a local tenant.
- `0002` quote: the payer no longer re-derives the payee's ILP condition with its own secret (only possible when both
  FSPs share one secret); it checks the packet carries this quote and the condition is a 32-byte SHA-256. The
  fulfilment is checked at commit by the switch (see Known gaps).
Everything else is done around the source:
- `services/zeebe-on-fluxnova` replaces the Zeebe client (same API subset, on Fluxnova's REST API); the init script
  swaps it in (`io.camunda:zeebe-client-java` → `dev.difme:zeebe-client-fluxnova`). connector-common declares the Zeebe
  client without using it: dropped there. connector-channel uses Guava without declaring it (it came with the Zeebe
  client's gRPC stack): declared by the init script.
- Flows are converted by `ci/zeebe-bpmn-to-fluxnova.py` (`bill-request-DFSPID.bpmn` is an incomplete upstream draft:
  skipped). DIFME's own flow `processes/ph-ee/DifmeOnUsTransfer-DFSPID.bpmn` is written in the same Zeebe dialect from
  PH-EE task types only.

Licence fixes (owner rule: free and open source only), all in the init script, no source change:
- `org.mifos:interop-ilp-conditions` (needed by connector-mojaloop) is "All Rights Reserved ... use is prohibited
  without permission": replaced by `services/fspiop-ilp-models` (FSPIOP models written from the Mojaloop API
  specification, same package) and the vendored Apache-2.0 `vendor/interledger/java-ilp-core`.
- `org.json:json` before 20231013 is under the JSON License (not OSI): forced to 20240303 (public domain) everywhere.
- Rules are registered again after each project is evaluated: Spring's dependency-management plugin otherwise
  re-applies the declared versions.

Settings the connectors need beyond upstream's defaults (DIFME runtime config):
- `zeebe.broker.contactpoint`: Fluxnova's engine REST URL; `dfspids`: the Fineract tenant (worker topics are named after it).
- `security.jws.enable` / `security.jws.response.enable: false` (connector-common 1.9.1 conditions on them).
- connector-channel: `bpmns.tenants[].flows.payment-transfer` (a missing list is a NullPointerException on every
  transfer) and `redis.*` (its request idempotency store; DIFME's Valkey in the cluster).
- connector-ams-mifos: `ams.local.*` hosts and tenants, and `ams.local.keystore-path` (it loads a keystore at start
  even for plain-HTTP Fineract: a throwaway one is generated, upstream's is not used).

Upstream quirk noted: the payee-deposit worker completes its job twice (from the Fineract route, then unconditionally);
the second completion is refused as on Zeebe, and the first (real) outcome stands.

Verified 2026-11-21 (local, Fluxnova 3.1.1 + Fineract 1.16.1): a transfer posted to PH-EE's own channel API
(`POST /channel/transfer`) ran DifmeOnUsTransfer on Fluxnova through PH-EE's Fineract connector: local quote → block →
payee deposit → book → success; payer 10,000 → 8,500, payee 0 → 1,500. Over the balance: Fineract's interop quote
refuses (403), the flow ends at "send error to channel", balances unchanged.

Cross-bank, verified 2026-11-21 (local): PH-EE channel API → PayerFundTransfer on Fluxnova → party lookup, quote,
transfer through the Mojaloop switch (Testing Toolkit hub) to the other bank (SDK scheme adapter + mojaloop-simulator)
→ COMMITTED → payer booked in Fineract (8,500 → 8,300 INR). Needed besides the patches: the ILP v1 envelope fix in
java-ilp-core (`vendor/interledger`), `parties` (tenant → Mojaloop fspId) and `switch.*-host` in the runtime config.

Known gaps:
- Fulfilment vs condition at commit: Mojaloop's switch core (central-ledger) checks it; the Testing Toolkit hub does
  not, and PH-EE's payer side does not either. To close with the real switch core or a payer-side check.
- After COMMITTED the other bank's SDK asks the payer for the transfer (`GET /transfers/{id}`), which PH-EE's
  connector does not serve; harmless for the payment, noisy in the switch log.
