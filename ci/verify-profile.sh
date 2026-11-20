#!/usr/bin/env bash
# Install one profile on the running cluster and judge it: converged, sync failure or timeout. Prints the memory
# footprint before/after. Exit 0 only when every Argo CD app is Synced+Healthy.
#   ci/verify-profile.sh <profile> [deadline-seconds]
set -uo pipefail
cd "$(dirname "$0")/.."
P=${1:?profile}; DEADLINE=${2:-1800}
echo "== $P: before"; bash ci/mem-report.sh 3 | head -4
t=$(date +%s); make up PROFILE="$P" > "/tmp/up-$P.log" 2>&1; echo "UP_EXIT $? $(( $(date +%s)-t ))s"
grep -E "⚠|error" "/tmp/up-$P.log" | head -5
apps() { kubectl -n argocd get applications -o jsonpath='{range .items[*]}{.metadata.name} {.status.operationState.phase} {.status.sync.status} {.status.health.status}{"\n"}{end}'; }
end=$(( $(date +%s) + DEADLINE )); firstfail=0; rc=1
while :; do
  bad=$(apps | grep -vE "Succeeded Synced Healthy|^fleet-bundles|Synced Healthy$")
  [ -z "$bad" ] && { echo "CONVERGED $P $(( $(date +%s)-t ))s"; rc=0; break; }
  if echo "$bad" | grep -qE " (Failed|Error) "; then
    [ $firstfail -eq 0 ] && firstfail=$(date +%s)
    if [ $(( $(date +%s)-firstfail )) -gt 180 ]; then
      echo "SYNC FAILURE $P"; echo "$bad"
      kubectl -n argocd get applications -o jsonpath='{range .items[?(@.status.operationState.phase=="Failed")]}{.metadata.name}: {.status.operationState.message}{"\n"}{end}' | cut -c1-400
      break
    fi
  else firstfail=0; fi
  [ "$(date +%s)" -gt "$end" ] && { echo "TIMEOUT $P"; echo "$bad"; break; }
  sleep 20
done
if [ $rc -ne 0 ]; then
  echo "== pods not ready"; kubectl get pods -A --no-headers | grep -vE "Running|Completed" | cut -c1-120 | head -25
  # Crash-looping pods, plus pods that run but never turn ready (e.g. a new revision failing its probe).
  for p in $(kubectl get pods -A --no-headers | awk '$4 ~ /CrashLoop|Error/ || ($4 == "Running" && $3 ~ /^0\//) {print $1"/"$2}' | head -8); do
    echo "--- logs $p"; kubectl -n "${p%%/*}" logs "${p#*/}" --all-containers --tail=40 2>&1 | cut -c1-300
    kubectl -n "${p%%/*}" logs "${p#*/}" --all-containers --previous --tail=15 2>/dev/null | sed 's/^/  (previous) /' | cut -c1-300
    kubectl -n "${p%%/*}" describe pod "${p#*/}" 2>/dev/null | sed -n '/^Events:/,$p' | tail -8
  done
  kubectl get rollouts -A 2>/dev/null | head; kubectl get rs -n core-platform 2>/dev/null | head -15
  echo "== recent warning events"; kubectl get events -A --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -20 | cut -c1-250
fi
echo "== $P: after"; bash ci/mem-report.sh 12
# Process/thread level (owner rule 2026-11-20): every process in every pod, its PSS, threads and thread groups.
echo "== $P: processes"; sudo -n env "PATH=$PATH" "KUBECONFIG=${KUBECONFIG:-$HOME/.kube/config}" bash ci/proc-report.sh 45 2>/dev/null || bash ci/proc-report.sh 45
exit $rc
