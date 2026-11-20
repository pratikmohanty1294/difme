# GitOps / Argo CD

### Manual `helm upgrade` / `kubectl edit` changes disappear
- **Root cause:** apps use automated sync with self-heal, so Argo CD reverts live edits.
- **Rule:** every change goes through Git. Use live `helm`/`kubectl` only for *dry-run validation*, or before Argo CD manages the object.

### Apps show `OutOfSync` although the sync succeeded
- **Root cause 1:** Cilium copies pod labels, including Argo CD's tracking label, onto `CiliumIdentity` objects.
  - **Fix:** annotation-based tracking, and exclude Cilium objects (`deploy/bootstrap/argocd-cm-patch.yaml`).
- **Root cause 2:** controllers and webhooks fill in defaults.
  - **Fix:** server-side diff (`controller.diff.server.side`), plus narrow `ignoreDifferences` in `deploy/argocd/render.py`, e.g. the webhook `caBundle` and selectors injected at runtime.

### A fix in Git never reaches the cluster; the same old error repeats
- **Root cause:** `RespectIgnoreDifferences` with `managedFieldsManagers: [kyverno]` made Argo CD re-apply the **old live** `.spec.rules` of ClusterPolicies, silently dropping the change.
- **Fix:** no ignore rules on the `policies` app.
- **Prevention:** only ignore fields that are truly runtime-owned (jq paths), never a whole field manager on objects you edit.

### Retries keep failing on a revision that has already been fixed
- **Root cause:** one sync *operation* retries its original revision; auto-sync used up its 5 retries.
- **Fix:** `argocd app terminate-op <app> --core`, then `argocd app sync <app> --core`.

### `policies` app stuck at `Unknown`: server-side diff fails on one object
- **Root cause:** the whole app's diff fails if any object is rejected by a webhook dry run, so a fix that lives in the *same* app never syncs (chicken-and-egg).
- **Fix:** put prerequisites (e.g. RBAC) in the component that owns them (Kyverno chart values), not in the app that needs them.

### Gatekeeper constraints fail: CRD not found
- **Root cause:** constraints were applied before their ConstraintTemplates had created the CRDs.
- **Fix:** sync-wave 1 plus `SkipDryRunOnMissingResource=true` on constraints.

### High Argo CD CPU (300–900m bursts)
- **Root cause:** watching high-churn objects it doesn't manage (policy reports, Gatekeeper status, Cilium endpoints, leases, events).
- **Fix:** `resource.exclusions`, `ignoreResourceUpdatesEnabled`, 300 s reconcile. Result: 4–6m.

### Deleted a PostSync hook Job by hand: the app never re-ran it
- **Root cause:** the sync operation was still "waiting for completion of hook" for the deleted Job, so a new sync request queued behind it.
- **Fix:** remove `.operation`, null `.status.operationState`, then request a sync.
- **Prevention:** to re-run a hook, request a sync; don't delete the Job.

### Envoy Gateway schema rules (found by server dry run)
- A `ReplacePrefixMatch` URL rewrite needs exactly **one** PathPrefix match per rule.
- `ReplacePrefixMatch` replaces the whole matched prefix: `/api/settlements` → `/v1/settlements`, not `/v1`.
- `BackendTrafficPolicy` local rate-limit `clientSelectors` take headers/CIDRs, not paths. For per-API limits, use one HTTPRoute per API with its own policy.

### Istio charts: repo returns 403
- **Root cause:** Istio moved its Helm repo from `istio-release.storage.googleapis.com/charts` to `https://blob.istio.io/istio-release/charts`. The old bucket returns 403, and `oci://gcr.io/istio-release/charts` fails too.
- **Fix:** new repo, and bumped to 1.32.0.
- **Prevention:** CI's "Helm charts resolve at pinned versions" gate caught it. When a repo fails, check the project's install docs before trying alternatives.

### Withdrawn `bitnami/kubectl`, round two
- VictoriaMetrics k8s-stack (`victoria-metrics-operator.crds.cleanup.image`) and Velero (`kubectl.image`) render it in hook Jobs. Both now use `registry.k8s.io/kubectl:v1.32.3`.
- The CI guard catches these, so any new chart that runs kubectl hooks needs the same override.

### Kyverno `kyverno-clean-reports`: `exec: "/bin/bash": not found`
- **Root cause:** after swapping the withdrawn `bitnami/kubectl` for `registry.k8s.io/kubectl` (distroless, no shell), the chart's legacy report-migration job could no longer run its bash script.
- **Fix:** `policyReportsCleanup.enabled: false`. It only migrates reports from old Kyverno releases, which a fresh install doesn't have. `webhooksCleanup` calls kubectl directly and keeps working.
- **Prevention:** when replacing a chart's helper image, check whether its jobs use a shell (`command: [/bin/bash, …]`).

### Argo Rollouts `workloadRef` without its metrics backend doubles the pods (found 2026-11-18)
- The cmd-svc canary Rollout's analysis queries VictoriaMetrics, which only the observability profile installs. In core alone the analysis could never pass.
- So the Rollout stayed on its first template (from before `/tmp` was added; it crash-looped on a read-only FS) and the Deployment kept running beside it. Two controllers ran one service, and ReplicaSets piled up with each restart.
- Fix: the Rollouts moved into a `canary-gates` component in the observability profile.
- Also: `kubectl rollout restart` on an Argo-managed Deployment adds an annotation that self-heal reverts, which leaves another ReplicaSet behind. Delete the pods instead.

### Argo Rollouts and Argo CD fought over the Deployment's `replicas` (found 2026-11-20)
- **Symptom:** with core + observability, a `quarkus-cmd-svc` pod from the Deployment's own ReplicaSet was started and killed about once a minute. It never got as far as listening on 8080 (readiness: connection refused). `canary-gates` and `core-services` stayed `Progressing`, and the profile timed out.
- **Root cause:** the Rollout uses `workloadRef` with `scaleDown: progressively`, so Argo Rollouts scales the referenced Deployment to 0. The generated Deployment declared `replicas: 1`, and Argo CD self-heal set it back each time.
- **Fix:** services with `rollout: true` in `core-services/services.yaml` leave `replicas` out of the Deployment, as KEDA-scaled services already do. The API default of 1 still applies when no Rollout exists (core alone).
- **Rule:** a field that another controller writes (HPA/KEDA, Argo Rollouts) must not be declared in the Git manifest of a self-healing app.

### First real streaming install: seven defects, about 50 min lost (found 2026-11-18)
- **Kyverno cost labels:** `require-labels-and-limits` rejected every third-party operator in `streaming` (Strimzi, KEDA, Apicurio, Flink, RisingWave). Fix: a mutate rule in `mutate-defaults.yaml` adds `difme/team: platform`, `business.metric: shared-infra` only where missing.
- **`$(VAR)` env references:** `no-plaintext-secrets` treated `RW_SQL_PASSWORD: $(RW_POSTGRES_PASSWORD)` (a reference to a Secret-backed variable) as a literal. The pattern now allows `'\$(*)'`. The `\$` escapes Kyverno's own `$(...)` syntax.
- **Strimzi and Kafka versions:** Strimzi 0.45 supports Kafka only up to 3.9, but `kafka.yaml` asks for 4.0.0. Moved to 0.47.0, the newest that still supports 4.0.0.
- **Uncommitted config:** `kafka.yaml` referenced a `kafka-metrics` ConfigMap that was never committed (now `kafka-metrics.yaml`).
- **Missing `username` key:** `risingwave-db` lacked it (fixed in `dev-secrets.sh`). Its first crash left a half-applied meta migration in YugabyteDB ("Table `cluster` already exists"), so the database had to be dropped and recreated.
- **Apicurio:** the registry and UI images both default to port 8080 in one pod; the registry now uses 8081. With no datasource username it defaults to `sa`; it now uses `apicurio`.
- **Debezium:** Strimzi's in-cluster `build:` needs a push registry and credentials, which a dev cluster doesn't have. The Connect image is now built in CI (`deploy/images/debezium-connect`).
- **Operational lessons:**
  - Manual `kubectl apply` on an Argo-managed object is reverted by self-heal. Change it in git and sync its app, policies first.
  - A manual `operation.sync` patch must repeat the app's `syncOptions` (`ServerSideApply=true`), or large CRDs fail the 256 KiB annotation limit.
  - Watchers must fail fast on sync or pod errors and have a deadline. Waiting only for "Healthy" hid a hard failure for 50 min.
- **Python on Windows writes CRLF:** normalize line endings before committing (`sed -i 's/\r$//'`).
- **Later the same day:**
  - **Connect image CVEs:** blocked by 5 CRITICAL CVEs. Some came from the Strimzi base image: Cruise Control's Netty and kafka_exporter's Go stdlib (both unused by Connect, so deleted) and ca-certificates (updated). Old Debezium 3.1 and Apicurio 3.0.6 jars brought Netty and Jersey. Fix: Strimzi 0.51 / Kafka 4.2, Debezium 3.7.1, Apicurio 3.3.4; scan locally with the CI Trivy command before pushing.
  - **Bootstrap Service name:** Strimzi names it `<cluster>-kafka-bootstrap`. The cluster was `difme-kafka`, but 15 clients use `difme-kafka-bootstrap`, so the cluster was renamed `difme`. The rename left the old StrimziPodSet and PVC behind (about 600 MiB) until they were deleted by hand.
  - **Strimzi entity operator and Kyverno:** it sets env vars that *name* Secrets (`*_SECRET_NAME`). `no-plaintext-secrets` is now a foreach/deny rule that only rejects credential-like names carrying a literal value.
  - **Apicurio on YugabyteDB:** it works when its first start isn't interrupted. The port clash had interrupted it, which left `idx_versions_8` behind, so the database had to be recreated.
  - **RisingWave meta store:** it can't run on YugabyteDB, so it moved to the shared Postgres (ADR-019 amendment). SeaweedFS moved into the streaming profile.
  - **Flink operator webhook:** it requires `execution.checkpointing.savepoint-dir` when `upgradeMode: savepoint`.

### Kyverno autogen applied the dev request scaling twice (found 2026-11-18)
- **Cause:** a Pod `mutate` rule is auto-generated for Deployment/StatefulSet/Job templates by default. `dev-request-scaling` therefore divided the template's requests, and the pod created from that template was divided again.
  - TigerBeetle got 12m/550Mi instead of 50m/1100Mi, and its startup (a 1 GiB journal scan) was too slow. It was SIGTERMed (exit 143).
- **Fix:** `pod-policies.kyverno.io/autogen-controllers: none`, plus a once-only marker annotation.
- **Cleanup after the fact:**
  - remove the marker the autogen copied into templates (`/spec/template/metadata/annotations/difme.dev~1requests-scaled`);
  - force-sync every app (`syncStrategy.apply.force: true`). Kyverno now owns the mutated request fields, so a normal server-side sync does not take them back.
- **Rule:** any mutate policy that does arithmetic on existing values must be idempotent and pods-only.
- **Separate finding from the same rebuild:** the Argo CD controller SIGTERM loop (about 8 s after each start, with no kubelet, containerd or Argo reason) did not survive a Codespace container rebuild. The cause was leftover node/Codespace state; a reinstall of Argo CD alone did not help.

### Argo CD sync ping-pong burned ~1.8 cores at idle (found 2026-11-19)
- **Two causes:**
  - **Shared resources:** two Applications declared the same resources (`ServiceAccount/flink` and `Role/flink` in `flink-jobs` *and* the flink-operator chart). Each app's auto-sync re-applied them, and the other app went OutOfSync.
  - **Autoscaled replicas:** `replicas: 1` on Deployments that KEDA scales to 2.
- **How it showed up:** idle node CPU 64%; k3s up to 260%; Argo CD controller 550m; 166 automated syncs per 5 min.
- **How to find it:**
  - count reconciles per app: `kubectl -n argocd logs argocd-application-controller-0 --since=5m | grep "Reconciliation completed" | grep -o "application=[a-z0-9-]*" | sort | uniq -c`;
  - see the drift: `kubectl -n argocd exec argocd-application-controller-0 -- argocd app diff <app> --core`.
- **Fix:**
  - exactly one owner per resource (the chart keeps the Flink RBAC);
  - no `replicas` on autoscaled workloads (the `autoscaled: true` flag in `services.yaml`).
- **Effect:** idle CPU 0.6–0.9 cores; accepted payments 6.6 → 10.3 req/s.

### Fluxnova never turned ready: the readiness probe hit an authenticated endpoint (found 2026-11-20)
- **Symptom:** in the process profile, Fluxnova ran but stayed 0/1, and `deploy-process-models` waited on it forever.
- **Root cause:** `fluxnova.bpm.run.auth.enabled: true` registers the Basic-auth filter on the whole REST path (`FluxnovaBpmRunRestConfiguration`, URL pattern = Jersey application path, i.e. `/engine-rest/*`). The probe's unauthenticated `GET /engine-rest/version` got 401, which the kubelet counts as a failure.
- **Fix:** `tcpSocket` probe on 8080. Spring Boot starts Tomcat's connector only after the context, including the engine and schema update, is up.
- **Rule:** a probe must target an endpoint that needs no credentials. Check the app's auth filter coverage when turning auth on.
