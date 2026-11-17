#!/usr/bin/env bash
# Bootstraps Cilium + Argo CD + the app-of-apps root. Everything after this is GitOps.
set -euo pipefail
REPO=${1:-https://github.com/pratikmohanty1294/difme.git}
API=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}')

helm repo add cilium https://helm.cilium.io >/dev/null
helm upgrade --install cilium cilium/cilium -n kube-system --version 1.17.3 \
  -f deploy/platform/kube-system/cilium/values.yaml --set k8sServiceHost="$API" --set k8sServicePort=6443 --wait --timeout 10m

kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argocd --server-side -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.14.11/manifests/install.yaml
kubectl -n argocd rollout status deploy/argocd-server --timeout=300s
# Kustomize dirs pull shared files (flags, Lua scripts, dashboards) from elsewhere in the repo.
kubectl -n argocd patch configmap argocd-cm --type merge --patch-file deploy/bootstrap/argocd-cm-patch.yaml
# Server-side diff (controller-defaulted fields aren't drift) and generous repo/Helm timeouts so large chart
# downloads on slow links don't fail a sync (ADR-012 network I/O).
kubectl -n argocd patch configmap argocd-cmd-params-cm --type merge -p '{"data":{"controller.diff.server.side":"true","controller.repo.server.timeout.seconds":"300","reposerver.parallelism.limit":"2"}}'
kubectl -n argocd set env deploy/argocd-repo-server ARGOCD_EXEC_TIMEOUT=300s ARGOCD_GIT_ATTEMPTS_COUNT=5 >/dev/null
kubectl -n argocd rollout restart statefulset/argocd-application-controller deploy/argocd-repo-server >/dev/null
# Argo Rollouts lives in its own namespace: its RBAC binds the argo-rollouts/argo-rollouts service account (found live).
kubectl create namespace argo-rollouts --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argo-rollouts --server-side -f https://github.com/argoproj/argo-rollouts/releases/download/v1.8.2/install.yaml

sed "s#__REPO__#$REPO#g" deploy/argocd/project.yaml | kubectl apply -f -
# The base profile is always on: infra layer 11 (namespaces, CRDs, policy, ingress).
for a in $(grep -v '^#' deploy/profiles/base.txt); do kubectl apply -f deploy/argocd/apps/$a.yaml; done
echo "Argo CD admin password: $(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)"
