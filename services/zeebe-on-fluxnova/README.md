# zeebe-client-fluxnova

The part of the Zeebe Java client API that Mifos Payment Hub EE (vendor/mifos-ph-ee) uses, implemented on Fluxnova's
engine REST API, so the PH-EE connectors run on Fluxnova **without source changes** (owner decision 2026-11-21: reuse
Mifos PH-EE, replace only Zeebe). The connectors' builds swap `io.camunda:zeebe-client-java` for this library
(`deploy/images/mifos-ph-ee/vendored-common.gradle`); `zeebe.broker.contactpoint` becomes Fluxnova's REST URL.

| Zeebe | Fluxnova |
|---|---|
| job worker on a job type | external-task long poll on the topic (`fetchAndLock`) |
| complete / fail / throw error | external task complete / failure (retries) / bpmnError |
| handler throws | failure with retries − 1 (at 0 the engine raises an incident) |
| create instance (latest version) | `process-definition/key/{id}/start` |
| publish message (correlation key, TTL) | `message` correlation on the flow's correlation variable(s); retried until the TTL when no instance waits yet (Zeebe buffers) |
| set variables / cancel / update retries | execution or instance variables / delete instance / external-task retries |
| 64-bit keys | issued here for Fluxnova's string ids (in memory) |

The flows are converted by `ci/zeebe-bpmn-to-fluxnova.py` (task types → external-task topics, message subscriptions →
`zeebe-on-fluxnova-messages.properties`, FEEL conditions kept as `language="feel"`, timer variables → `${var}`).

Tests: `FluxnovaLiveTest` runs a PH-EE-style Zeebe flow, converted, on a real Fluxnova (set `FLUXNOVA_URL` and
`PORT_CHECK_BPMN`): early message buffered, FEEL branches, timer from a variable, retried handler, cancel by key.

Known limits: keys live in memory (a job completed after a restart of this process fails; the engine re-delivers it
once its lock expires); `newResolveIncidentCommand` is a no-op because setting retries resolves the incident in
Fluxnova; custom task headers are not carried (PH-EE's flows define none).
