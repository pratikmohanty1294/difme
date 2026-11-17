#!/usr/bin/env bash
# Installs every CLI the platform needs. Runs once when the Codespace is created.
set -euo pipefail
BIN=/usr/local/bin
arch=$(dpkg --print-architecture)

curl -fsSL https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | TAG=v5.8.3 bash
curl -fsSL -o /tmp/argocd https://github.com/argoproj/argo-cd/releases/download/v2.14.11/argocd-linux-${arch} && sudo install /tmp/argocd $BIN/argocd
curl -fsSL https://github.com/cilium/cilium-cli/releases/download/v0.18.3/cilium-linux-${arch}.tar.gz | sudo tar xz -C $BIN
curl -fsSL https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv5.6.0/kustomize_v5.6.0_linux_${arch}.tar.gz | sudo tar xz -C $BIN
curl -fsSL https://github.com/bufbuild/buf/releases/download/v1.50.0/buf-Linux-x86_64 -o /tmp/buf && sudo install /tmp/buf $BIN/buf
curl -fsSL https://github.com/sigstore/cosign/releases/download/v2.4.3/cosign-linux-${arch} -o /tmp/cosign && sudo install /tmp/cosign $BIN/cosign
curl -fsSL https://raw.githubusercontent.com/anchore/syft/main/install.sh | sudo sh -s -- -b $BIN v1.20.0
curl -fsSL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sudo sh -s -- -b $BIN v0.59.1
curl -fsSL https://github.com/grafana/k6/releases/download/v0.57.0/k6-v0.57.0-linux-${arch}.tar.gz | sudo tar xz --strip-components=1 -C $BIN
curl -fsSL https://github.com/tigerbeetle/tigerbeetle/releases/download/0.16.78/tigerbeetle-x86_64-linux.zip -o /tmp/tb.zip && sudo unzip -o /tmp/tb.zip -d $BIN
curl -fsSL https://github.com/temporalio/cli/releases/download/v1.3.0/temporal_cli_1.3.0_linux_${arch}.tar.gz | sudo tar xz -C $BIN temporal
sudo mkdir -p /opt/tla && sudo curl -fsSL -o /opt/tla/tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar
curl -fsSL https://raw.githubusercontent.com/WasmEdge/WasmEdge/master/utils/install_v2.sh | bash -s -- -v 0.14.1
pipx install semgrep==1.108.0
pipx install dbt-core==1.9.2 && pipx inject dbt-core dbt-trino==1.9.0
rustup target add wasm32-wasip1 aarch64-linux-android
npm i -g @asyncapi/cli@2.16.0 newman@6.2.1 @stoplight/spectral-cli@6.14.2
echo "DIFME Codespace ready. Run: make cluster && make up PROFILE=core"
