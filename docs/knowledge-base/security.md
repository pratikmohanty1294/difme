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
- **Root cause:** a vendor-branded chart catalog was withdrawn from docker.io (it is also vendor-named).
- **Fix:** `registry.k8s.io/kubectl` via chart values.
- **Prevention:** the `verify` workflow fails if any chart renders a `bitnami/` image.

### Argo Rollouts crash: cannot get configmap
- **Root cause:** its manifest was installed into the `argocd` namespace, but its RBAC binds the `argo-rollouts` service account.
- **Fix:** install into the `argo-rollouts` namespace.

### Kyverno `+(value)` on env entries corrupts containers (A/B 44687672715, 2026-11-20) — rejected
- **Symptom:** with the `default-malloc-arena-max` rule (add `MALLOC_ARENA_MAX` where absent), base and core failed to sync: `Deployment "temporal-frontend" is invalid: env[0].valueFrom: may not be specified when value is not empty`.
- **Root cause:** the add-if-absent anchor inside an env list, applied through Kyverno's autogen rules to Deployment templates, did not merge by env name; it added `value` to an existing entry that uses `valueFrom`.
- **Rule:** don't inject env defaults with `patchStrategicMerge` + `+(value)`. Use a `foreach` over containers with a precondition that the variable is absent and a JSON patch that appends to `/env/-` (and handle containers without an env list), or set the variable per component. Verify any cluster-wide mutation with cluster-verify before merging.
### First security-profile install: five defects (cluster-verify 2026-11-20)
- **OpenBao:** 3-replica Raft HA can't schedule on one node, and nothing initialized or unsealed it. **Fix (owner decision):** chart 0.30.7 (OpenBao 2.7.2), one Raft replica, the **static seal** (auto-unseal from a 32-byte dev key in Secret `openbao-unseal`) and **self-initialization** (`initialize` stanzas) creating the `pki_int` CA, Kubernetes auth and the `spire-server` role/policy on first start. No bootstrap Job and no root token left behind. Both features need OpenBao ≥ 2.4. Production swaps the static key for a KMS/transit seal.
- **SPIRE:** Argo CD runs Helm `pre-upgrade` hooks as PreSync even on first install; the chart's hook patches a webhook that doesn't exist yet and failed every time. **Fix:** `global.installAndUpgradeHooks.enabled: false`. Also `upstreamAuthority.vault.insecureSkipVerify: true`, since in-cluster OpenBao is plain HTTP and the chart otherwise mounts a non-existent `vault-ca` Secret.
- **Istio ambient:** `istio-system` enforced Pod Security "baseline", which rejects istio-cni (AppArmor unconfined) and ztunnel (extra capabilities). **Fix:** `privileged` for that namespace only, like the other node-agent namespaces.
- **Veraison:** the verification and provisioning containers create `/opt/veraison/stores` at start-up on a read-only root filesystem. **Fix:** they mount the pod's `stores` emptyDir too.
- **attestation-verifier:** no source and no image exist yet. **Fix (owner decision):** left out of the profile until its code exists.

### Istio ambient on Cilium: core services crash-looped once their namespaces joined the mesh (cluster-verify 2026-11-20)
- **Symptom:** after the security profile installed, every pod in core-platform and temporal failed its probes ("context deadline exceeded") and was restarted by its liveness probe; istio-cni stayed Progressing.
- **Root causes (Istio's Cilium prerequisites, none applied):** kubelet probes into ambient pods are SNAT-ed to 169.254.7.127, which our default-deny Cilium policies dropped; Cilium's exclusive CNI mode removes istio-cni's chained plugin; istio-cni looked for the CNI config in /etc/cni/net.d, but k3s keeps it under /var/lib/rancher/k3s/agent/etc/cni/net.d.
- **Fix (owner decision: keep Istio ambient):** `istio-config/allow-ambient-probes.yaml` (CiliumClusterwideNetworkPolicy from 169.254.7.127/32, `enableDefaultDeny` off so it only adds an allow); Cilium `cni.exclusive: false`; istio-cni `global.platform: k3d`.
