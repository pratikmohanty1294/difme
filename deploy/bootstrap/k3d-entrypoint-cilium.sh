#!/bin/sh
# Runs inside each k3d server node before k3s starts (k3d executes /bin/k3d-entrypoint-*.sh).
# Cilium needs a shared bpffs and a shared cgroup2 hierarchy so its socket load balancer covers host-network pods.
# Every step is best-effort: a failure here must never stop the node from booting.
log() { echo "[k3d-entrypoint-cilium] $*"; }
mountpoint -q /sys/fs/bpf || mount -t bpf bpffs /sys/fs/bpf || log "bpffs mount failed"
mount --make-shared /sys/fs/bpf 2>/dev/null || log "make-shared /sys/fs/bpf failed"
mkdir -p /run/cilium/cgroupv2
mountpoint -q /run/cilium/cgroupv2 || mount -t cgroup2 none /run/cilium/cgroupv2 || log "cgroup2 mount failed"
mount --make-shared /run/cilium/cgroupv2 2>/dev/null || log "make-shared cgroup2 failed"
log "done"
exit 0
