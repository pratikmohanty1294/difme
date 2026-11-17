#!/usr/bin/env bash
# Pre-pulls every image a profile needs into the k3d node BEFORE Argo CD creates pods (ADR-012 network I/O):
#   - images come through the persistent pull-through caches (make registries), so repeats are local-disk speed;
#   - at most 3 pulls run in parallel (large images don't starve small ones, the link isn't saturated);
#   - each pull retries with exponential backoff, so a slow or flaky network never surfaces as pod crash loops.
# Usage: ci/prefetch.sh <profile> [node]
set -uo pipefail
cd "$(dirname "$0")/.."
PROFILE=${1:-core}
NODE=${2:-k3d-difme-server-0}
PARALLEL=${PREFETCH_PARALLEL:-3}
apps=$(grep -v '^#' "deploy/profiles/$PROFILE.txt")
tmp=$(mktemp -d)

# 1. Render every app in the profile and collect image references.
python3 - "$tmp/charts.txt" $apps <<'PY'
import sys, yaml
out, apps = sys.argv[1], set(sys.argv[2:])
with open(out, "w") as f:
    for c in yaml.safe_load(open("deploy/argocd/catalog.yaml"))["components"]:
        if c["name"] in apps:
            f.write(f'{c["name"]} {c["kind"]} {c["ns"]} {c.get("repo","-")} {c.get("chart","-")} {c.get("version","-")}\n')
PY
while read -r name kind ns repo chart version; do
  if [ "$kind" = helm ]; then
    if [[ "$repo" == *docker.io* ]]; then ref="oci://$repo/$chart"; else ref="$chart --repo $repo"; fi
    helm template "$name" $ref --version "$version" -n "$ns" -f "deploy/platform/$ns/$name/values.yaml" \
      --api-versions cert-manager.io/v1 2>/dev/null
  else
    kustomize build --load-restrictor LoadRestrictionsNone "deploy/platform/$ns/$name" 2>/dev/null
  fi
done < "$tmp/charts.txt" | grep -oE 'image: *"?[^" ]+"?' | sed -E 's/image: *"?([^"]+)"?/\1/' | sort -u > "$tmp/images.txt"

total=$(wc -l < "$tmp/images.txt")
echo "prefetch $PROFILE: $total images, $PARALLEL in parallel"

# 2. Pull into the node's containerd with retry + exponential backoff.
pull() {
  img=$1; node=$2
  docker exec "$node" crictl inspecti "$img" >/dev/null 2>&1 && { echo "  = $img"; return 0; }
  for attempt in 1 2 3 4 5; do
    if docker exec "$node" crictl pull "$img" >/dev/null 2>&1; then echo "  ✓ $img"; return 0; fi
    sleep $(( 2 ** attempt ))
  done
  echo "  ✗ $img (will be pulled by kubelet)"; return 0
}
export -f pull
xargs -a "$tmp/images.txt" -P "$PARALLEL" -I{} bash -c 'pull "$@"' _ {} "$NODE"
rm -rf "$tmp"
