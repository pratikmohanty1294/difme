#!/bin/sh
# Runs inside each k3d node before k3s starts (k3d executes /bin/k3d-entrypoint-*.sh).
# Cilium needs a shared bpffs and a shared cgroup2 hierarchy so its socket load balancer covers host-network pods.
set -e
mount bpffs /sys/fs/bpf -t bpf 2>/dev/null || true
mount --make-shared /sys/fs/bpf
mkdir -p /run/cilium/cgroupv2
mount -t cgroup2 none /run/cilium/cgroupv2 2>/dev/null || true
mount --make-shared /run/cilium/cgroupv2/
