# Cluster: k3d in Codespaces

### Pods can't reach the API service (`10.43.0.1`), CoreDNS / local-path / metrics-server crash
- **Root cause:** Cilium's full eBPF kube-proxy replacement doesn't work reliably inside Kubernetes-in-Docker. Host-network pods in particular can't reach ClusterIPs.
- **Fix:** Codespace runs Cilium **alongside k3s kube-proxy** with iptables masquerade (`deploy/platform/kube-system/cilium/values.yaml`, ADR-013). RKE2 clusters keep full kube-proxy replacement.
- **Prevention:** don't re-enable `kubeProxyReplacement` in k3d. Four variants were tried: legacy host routing, cgroup root, node entrypoint mounts, BPF masquerade. All failed or regressed.

### `BPF masquerade requires NodePort`
- **Root cause:** `bpf.masquerade: true` needs kube-proxy replacement's NodePort support.
- **Fix:** `bpf.masquerade: false` whenever kube-proxy replacement is off.

### Pods can't resolve DNS (CoreDNS → `172.18.0.1:53` i/o timeout)
- **Root cause:** k3d points nodes at the Docker gateway and relies on an iptables redirect that only covers node-originated traffic, not pod traffic.
- **Fix:** `K3D_FIX_DNS=0` plus k3s `--resolv-conf=/etc/difme/resolv.conf` (`deploy/bootstrap/k3s-resolv.conf`, mounted by `make cluster`).

### YugabyteDB crash loop: `Required ulimit values too low … open files 1024`
- **Root cause:** k3d node containers inherit Docker's default nofile limit.
- **Fix:** `options.runtime.ulimits` nofile 1048576 in `deploy/bootstrap/k3d.yaml`. Kafka and Cassandra need it too.

### Pods sit in `ContainerCreating` for 10+ minutes; dependants crash-loop meanwhile
- **Root cause:** large images (YugabyteDB ~2 GB, Temporal, FoundationDB) were downloaded serially, and again after every cluster rebuild.
- **Fix:**
  - Persistent pull-through caches (`make registries`) for docker.io, ghcr.io, quay.io and registry.k8s.io survive `make destroy`.
  - `make prefetch PROFILE=…` (run by `make up`) pre-pulls images 3 at a time with retry and exponential backoff.
  - The kubelet runs up to 3 parallel pulls.
- **Prevention:** add new images to manifests normally; prefetch discovers them from the rendered manifests.

### `gh codespace ssh` fails: no SSH server
- **Fix:** the `ghcr.io/devcontainers/features/sshd:1` feature in `.devcontainer/devcontainer.json`.
- **Note:** a rebuild uses the repo copy already inside the Codespace, so a devcontainer change needs a new Codespace (or `git pull` first).

### Post-create stopped half-way; several CLIs missing
- **Root cause:** `set -e` plus one failed download (the pinned Trivy release asset).
- **Fix:** every tool installs independently (`step` wrapper), and Trivy runs from its pinned image.
