# Security, policy engines, supply chain

### Own images rejected: `no matching attestations`
- **Root cause:** GitHub's attest actions store provenance and SBOM as Sigstore-bundle OCI referrers. Kyverno `verifyImages` reads **cosign-format** attestations.
- **Fix:** `_image.yml` also runs `cosign attest` (`slsaprovenance1`, `spdxjson`) with the same keyless workflow identity. The policy's SBOM type is `https://spdx.dev/Document`.
- **Prevention:** server dry-run new Deployments against the live admission webhooks before pushing.

### Kyverno keyless attestor invalid: `Rekor nil`
- **Fix:** every keyless attestor (signatures *and* attestations) needs `rekor: { url: https://rekor.sigstore.dev }`.

### Kyverno can't generate CiliumNetworkPolicies
- **Fix:** Kyverno chart values `admissionController/backgroundController.rbac.clusterRole.extraResources` (not a ClusterRole in the policies app; see the chicken-and-egg entry in gitops.md).

### MetalLB speaker blocked by Pod Security
- **Root cause:** namespaces enforce `baseline`; node agents need capabilities and host access.
- **Fix:** `privileged` only for `ingress-system`, `security`, `observability`, `backup`. Everything else stays `baseline`.

### Hook job `ImagePullBackOff`: `bitnami/kubectl` not found
- **Root cause:** the Bitnami catalog was withdrawn from Docker Hub (it is also vendor-named).
- **Fix:** `registry.k8s.io/kubectl` via chart values.
- **Prevention:** the `verify` workflow fails if any chart renders a `bitnami/` image.

### Argo Rollouts crash: cannot get configmap
- **Root cause:** its manifest was installed into the `argocd` namespace, but its RBAC binds the `argo-rollouts` service account.
- **Fix:** install into the `argo-rollouts` namespace.

### Kyverno `+(value)` on env entries corrupts containers (A/B 44687672715, 2026-11-20) — rejected
- **Symptom:** with the `default-malloc-arena-max` rule (add `MALLOC_ARENA_MAX` where absent), base and core failed to sync: `Deployment "temporal-frontend" is invalid: env[0].valueFrom: may not be specified when value is not empty`.
- **Root cause:** the add-if-absent anchor inside an env list, applied through Kyverno's autogen rules to Deployment templates, did not merge by env name; it added `value` to an existing entry that uses `valueFrom`.
- **Rule:** don't inject env defaults with `patchStrategicMerge` + `+(value)`. Use a `foreach` over containers with a precondition that the variable is absent and a JSON patch that appends to `/env/-` (and handle containers without an env list), or set the variable per component. Verify any cluster-wide mutation with cluster-verify before merging.
