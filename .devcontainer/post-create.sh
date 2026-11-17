#!/usr/bin/env bash
# Installs every CLI the platform needs. Runs once when the Codespace is created.
# Each tool installs independently: one failed download is reported and never blocks the others.
set -uo pipefail
BIN=/usr/local/bin
arch=$(dpkg --print-architecture)
failed=()

step() {  # step <name> <command...>
  local name=$1; shift
  if "$@"; then echo "✓ $name"; else echo "✗ $name"; failed+=("$name"); fi
}

step k3d        bash -c 'curl -fsSL https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | TAG=v5.8.3 bash'
step argocd     bash -c "curl -fsSL -o /tmp/argocd https://github.com/argoproj/argo-cd/releases/download/v2.14.11/argocd-linux-${arch} && sudo install /tmp/argocd $BIN/argocd"
step cilium     bash -c "curl -fsSL https://github.com/cilium/cilium-cli/releases/download/v0.18.3/cilium-linux-${arch}.tar.gz | sudo tar xz -C $BIN"
step kustomize  bash -c "curl -fsSL https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv5.6.0/kustomize_v5.6.0_linux_${arch}.tar.gz | sudo tar xz -C $BIN"
step buf        bash -c "curl -fsSL https://github.com/bufbuild/buf/releases/download/v1.50.0/buf-Linux-x86_64 -o /tmp/buf && sudo install /tmp/buf $BIN/buf"
step cosign     bash -c "curl -fsSL https://github.com/sigstore/cosign/releases/download/v2.4.3/cosign-linux-${arch} -o /tmp/cosign && sudo install /tmp/cosign $BIN/cosign"
step syft       bash -c "curl -fsSL https://raw.githubusercontent.com/anchore/syft/main/install.sh | sudo sh -s -- -b $BIN v1.20.0"
# Trivy runs from its pinned image (same as CI), so no release download can break it.
step trivy      bash -c "printf '#!/bin/sh\nexec docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v \"\$PWD:/src\" -w /src aquasec/trivy:0.59.1 \"\$@\"\n' | sudo tee $BIN/trivy >/dev/null && sudo chmod +x $BIN/trivy"
step k6         bash -c "curl -fsSL https://github.com/grafana/k6/releases/download/v0.57.0/k6-v0.57.0-linux-${arch}.tar.gz | sudo tar xz --strip-components=1 -C $BIN"
step tigerbeetle bash -c "curl -fsSL https://github.com/tigerbeetle/tigerbeetle/releases/download/0.16.78/tigerbeetle-x86_64-linux.zip -o /tmp/tb.zip && sudo unzip -o /tmp/tb.zip -d $BIN"
step temporal   bash -c "curl -fsSL https://github.com/temporalio/cli/releases/download/v1.3.0/temporal_cli_1.3.0_linux_${arch}.tar.gz | sudo tar xz -C $BIN temporal"
step tla        bash -c "sudo mkdir -p /opt/tla && sudo curl -fsSL -o /opt/tla/tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar"
step wasmedge   bash -c "curl -fsSL https://raw.githubusercontent.com/WasmEdge/WasmEdge/master/utils/install_v2.sh | bash -s -- -v 0.14.1"
step semgrep    pipx install semgrep==1.108.0
step dbt        bash -c "pipx install dbt-core==1.12.7 && pipx inject dbt-core dbt-trino==1.12.0"
step rust-targets rustup target add wasm32-wasip1 aarch64-linux-android
step npm-tools  npm i -g @asyncapi/cli@2.16.0 newman@6.2.1 @stoplight/spectral-cli@6.14.2

if [ ${#failed[@]} -eq 0 ]; then
  echo "DIFME Codespace ready. Run: make cluster && make up PROFILE=core"
else
  echo "DIFME Codespace ready with warnings — failed: ${failed[*]} (re-run: bash .devcontainer/post-create.sh)"
fi
