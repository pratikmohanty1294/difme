#!/usr/bin/env bash
# The node's image store (containerd, ~8 GB for core alone) lives on the Codespace's 118 GB /tmp disk, not the 32 GB
# root disk that filled up and caused an eviction loop (found 2026-11-18). Volumes and DB data stay on the root disk.
#   ci/image-disk.sh prepare   → before `k3d cluster create`
#   ci/image-disk.sh heal      → at Codespace start (postStartCommand): /tmp was emptied, Docker recreated the directory
#                                with /tmp's default ACL, and non-root pods can't traverse into their image layers
#                                ("stat /coredns: permission denied"). Fix the ACL, restart the node; images re-pull.
set -euo pipefail
DIR=${DIFME_IMAGE_DIR:-/tmp/difme-containerd}
NODE=${DIFME_NODE:-k3d-difme-server-0}
command -v setfacl >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq acl >/dev/null; }
fix() { sudo mkdir -p "$DIR"; sudo setfacl -R -b -k "$DIR"; sudo chmod 755 "$DIR"; }
has_default_acl() { getfacl -p "$DIR" 2>/dev/null | grep -q '^default:'; }
case "${1:-}" in
  prepare) fix; echo "image disk ready: $DIR" ;;
  heal)
    [ -d "$DIR" ] && ! has_default_acl && { echo "image disk OK"; exit 0; }
    if docker inspect "$NODE" >/dev/null 2>&1; then
      docker stop "$NODE" >/dev/null; fix; docker start "$NODE" >/dev/null
      echo "image disk healed; $NODE restarted (images re-pull from the registry caches)"
    else
      fix; echo "image disk prepared (no cluster yet)"
    fi ;;
  *) echo "usage: $0 prepare|heal"; exit 2 ;;
esac
