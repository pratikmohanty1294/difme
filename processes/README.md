# Business processes and rules (ADR-008)

Executable on **Fluxnova 3.1.1**. Each model uses the `fluxnova:` extension namespace (`http://fluxnova.finos.org/schema/1.0/bpmn`).

| File | Standard | Purpose |
|---|---|---|
| `bpmn/kyc-onboarding.bpmn` | BPMN 2.0 | KYC onboarding: application form → DMN risk → compliance review (HIGH risk) → account provisioning via Temporal → wait for callback |
| `dmn/kyc-risk.dmn` | DMN 1.3 | Business rule: risk level from country risk, PEP status and expected volume |
| `dmn/kyc-form-rules.dmn` | DMN 1.3 | **Form-field constraints and dependencies** for `forms/kyc-application.json` (required / visible / min / max per field) |
| `dmn/payment-limits.dmn` | DMN 1.3 | Business config: daily payment limit by channel and KYC level |
| `cmmn/dispute-case.cmmn` | CMMN 1.1 | Dispute case handling. CMMN is deprecated upstream and kept for compatibility. |

**Rules of the split:**
- Fluxnova owns business config, rules and form constraints, plus human steps.
- Every call to another process or service goes through Temporal. A service task with topic `temporal:<WorkflowType>` is picked up by `process-gateway`, which starts that Temporal workflow. The workflow reports back by correlating a BPMN message.

Every model is deployed to an in-memory Fluxnova engine and exercised in CI (`process-gateway` tests). The `process-models` Argo CD job deploys changed models to the cluster.
