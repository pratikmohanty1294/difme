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
  for p in $(kubectl get pods -A --no-headers | awk '$4 ~ /CrashLoop|Error/ {print $1"/"$2}' | head -6); do
    echo "--- logs $p"; kubectl -n "${p%%/*}" logs "${p#*/}" --all-containers --tail=25 2>&1 | cut -c1-300
  done
  echo "== recent warning events"; kubectl get events -A --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -20 | cut -c1-250
fi
echo "== $P: after"; bash ci/mem-report.sh 12
exit $rc
