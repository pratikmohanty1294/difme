#!/usr/bin/env bash
# The node's image store (containerd, ~8 GB for core alone) lives on the Codespace's 118 GB /tmp disk, not the 32 GB
# root disk that filled up and caused an eviction loop (found 2026-11-18). Volumes and DB data stay on the root disk.
#   ci/image-disk.sh prepare   → before `k3d cluster create`
#   ci/image-disk.sh swap      → only the swap/zswap step below
#   ci/image-disk.sh heal      → at Codespace start (postStartCommand): /tmp was emptied, Docker recreated the directory
#                                with /tmp's default ACL, and non-root pods can't traverse into their image layers
#                                ("stat /coredns: permission denied"). Fix the ACL, restart the node; images re-pull.
set -euo pipefail
DIR=${DIFME_IMAGE_DIR:-/tmp/difme-containerd}
NODE=${DIFME_NODE:-k3d-difme-server-0}
command -v setfacl >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq acl >/dev/null; }
fix() { sudo mkdir -p "$DIR"; sudo setfacl -R -b -k "$DIR"; sudo chmod 755 "$DIR"; }
# Memory (Tier 1, owner-approved 2026-11-18): the Codespaces host runs earlyoom, which SIGTERMs the largest process
# once available RAM drops below ~10% *and* free swap is below ~10%. The Codespace has no swap, so it fired at ~1.6 GB
# free and killed TigerBeetle and the Argo CD controller. zswap keeps swapped pages compressed in RAM (idle JVM/Go
# heaps compress 2-3x); the swapfile on the 118 GB /tmp disk is its overflow. /tmp is emptied on restart, so this
# runs on every start. The kubelet lets pods use it via memorySwap.swapBehavior: LimitedSwap (kubelet-difme.conf).
SWAP=${DIFME_SWAP_FILE:-/tmp/difme-swap}
swap() {
  if ! grep -q "^$SWAP " /proc/swaps; then
    [ -f "$SWAP" ] || { sudo fallocate -l "${DIFME_SWAP_SIZE:-16G}" "$SWAP"; sudo chmod 600 "$SWAP"; sudo mkswap -q "$SWAP" >/dev/null; }
    sudo swapon --priority 10 "$SWAP"
  fi
  Z=/sys/module/zswap/parameters
  if [ -w "$Z/enabled" ]; then
    echo zstd | sudo tee "$Z/compressor" >/dev/null 2>&1 || echo lz4 | sudo tee "$Z/compressor" >/dev/null 2>&1 || true
    echo zsmalloc | sudo tee "$Z/zpool" >/dev/null 2>&1 || true
    echo 25 | sudo tee "$Z/max_pool_percent" >/dev/null
    echo Y | sudo tee "$Z/enabled" >/dev/null
  fi
  echo "swap: $(awk -v f="$SWAP" '$1==f {printf "%d MiB", $3/1024}' /proc/swaps), zswap=$(cat $Z/enabled 2>/dev/null) $(cat $Z/compressor 2>/dev/null)"
}
has_default_acl() { getfacl -p "$DIR" 2>/dev/null | grep -q '^default:'; }
case "${1:-}" in
  prepare) fix; swap; echo "image disk ready: $DIR" ;;
  swap) swap ;;
  heal)
    swap
    [ -d "$DIR" ] && ! has_default_acl && { echo "image disk OK"; exit 0; }
    if docker inspect "$NODE" >/dev/null 2>&1; then
      docker stop "$NODE" >/dev/null; fix; docker start "$NODE" >/dev/null
      echo "image disk healed; $NODE restarted (images re-pull from the registry caches)"
    else
      fix; echo "image disk prepared (no cluster yet)"
    fi ;;
  *) echo "usage: $0 prepare|swap|heal"; exit 2 ;;
esac
