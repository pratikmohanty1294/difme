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
