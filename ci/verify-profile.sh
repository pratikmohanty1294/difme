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
# Fail fast (owner request 2026-11-20): a pod that is clearly broken ends the wait instead of the full deadline.
# Crash loop: >= 5 restarts and still crash-looping 3 min after first seen. Image/config errors: unchanged for 4 min.
# Startup crashes that recover (a service waiting for its database) stay below these bounds.
declare -A broken_since
broken_pods() {
  kubectl get pods -A --no-headers 2>/dev/null | awk '
    $4 == "CrashLoopBackOff" && $5+0 >= 5 { print $1"/"$2" crashloop 180"; next }
    $4 ~ /^(ImagePullBackOff|ErrImagePull|InvalidImageName|CreateContainerConfigError)$/ { print $1"/"$2" "$4" 240" }'
}
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
  failfast=""; now=$(date +%s); declare -A seen=()
  while read -r pod why limit; do
    [ -n "$pod" ] || continue
    seen[$pod]=1; : "${broken_since[$pod]:=$now}"
    [ $(( now - broken_since[$pod] )) -ge "$limit" ] && failfast="$failfast $pod($why)"
  done < <(broken_pods)
  for k in "${!broken_since[@]}"; do [ -n "${seen[$k]:-}" ] || unset "broken_since[$k]"; done   # recovered pods reset
  [ -n "$failfast" ] && { echo "FAILFAST $P after $(( now - t ))s:$failfast"; echo "$bad"; break; }
  [ "$(date +%s)" -gt "$end" ] && { echo "TIMEOUT $P"; echo "$bad"; break; }
  sleep 20
done
if [ $rc -ne 0 ]; then
  echo "== pods not ready"; kubectl get pods -A --no-headers | grep -vE "Running|Completed" | cut -c1-120 | head -25
  # Crash-looping pods, plus pods that run but never turn ready (e.g. a new revision failing its probe).
  for p in $(kubectl get pods -A --no-headers | awk '$4 ~ /CrashLoop|Error|Pending/ || ($4 == "Running" && $3 ~ /^0\//) {print $1"/"$2}' | head -8); do
    echo "--- logs $p"; kubectl -n "${p%%/*}" logs "${p#*/}" --all-containers --tail=40 2>&1 | cut -c1-300
    kubectl -n "${p%%/*}" logs "${p#*/}" --all-containers --previous --tail=15 2>/dev/null | sed 's/^/  (previous) /' | cut -c1-300
    # Errors of the previous run: a long config dump (Trino's Bootstrap table) pushes them out of the tail.
    kubectl -n "${p%%/*}" logs "${p#*/}" --all-containers --previous 2>/dev/null | grep -E 'ERROR|FATAL|Exception:|Caused by|panic' | grep -v '^\s*at ' | head -8 | sed 's/^/  (previous error) /' | cut -c1-300
    kubectl -n "${p%%/*}" describe pod "${p#*/}" 2>/dev/null | sed -n '/^Events:/,$p' | tail -8
    # Startup lines: setup steps log once at start and scroll out of --tail (formio's db setup, 2026-11-20).
    echo "  (startup)"; kubectl -n "${p%%/*}" logs "${p#*/}" --all-containers 2>/dev/null | grep -vE '^    at |Update found|[█▄▀]' | head -70 | cut -c1-300
    # The body of a failing HTTP readiness probe often names the cause (formio's /health 400, 2026-11-20).
    probe=$(kubectl -n "${p%%/*}" get pod "${p#*/}" -o jsonpath='{.spec.containers[0].readinessProbe.httpGet.port} {.spec.containers[0].readinessProbe.httpGet.path}' 2>/dev/null)
    if [ -n "${probe% *}" ] && [ "${probe#* }" != "$probe" ]; then
      echo "  readiness body (${probe#* }):"; kubectl get --raw "/api/v1/namespaces/${p%%/*}/pods/${p#*/}:${probe% *}/proxy${probe#* }" 2>&1 | head -c 600; echo
    fi
  done
  # Apps can stay Progressing while every pod is Ready (a FlinkDeployment whose job keeps failing, a Rollout, a Job).
  # Print the resources Argo CD itself marks unhealthy, with their status and events (found 2026-11-20: flink-jobs).
  echo "== unhealthy resources (Argo CD view)"
  for a in $(apps | awk '$4 != "Healthy" {print $1}' | grep -v '^fleet-bundles'); do
    kubectl -n argocd get application "$a" -o jsonpath='{range .status.resources[?(@.health.status)]}{.kind} {.namespace} {.name} {.health.status} {.health.message}{"\n"}{end}' \
      | awk '$4 != "Healthy"' | head -6 | while read -r kind ns name hs msg; do
        echo "--- $a: $kind $ns/$name $hs ${msg:-}" | cut -c1-300
        kubectl -n "$ns" get "$kind" "$name" -o jsonpath='{.status}' 2>/dev/null | cut -c1-900; echo
        kubectl -n "$ns" get events --field-selector "involvedObject.name=$name" --sort-by=.lastTimestamp 2>/dev/null | tail -5 | cut -c1-250
        if [ "$kind" = DaemonSet ]; then           # node agents (istio-cni, ztunnel): their own log says why they aren't ready
          kubectl -n "$ns" logs "ds/$name" --all-containers --tail=60 2>&1 | grep -viE 'ip6?tables|^-A |^-N |^\*|COMMIT' | tail -25 | cut -c1-300
        fi
        if [ "$kind" = FlinkDeployment ]; then
          kubectl -n "$ns" logs "deploy/$name" --tail=40 2>&1 | grep -iE 'exception|error|caused by|failed' | tail -15 | cut -c1-300
        fi
      done
  done
  if kubectl -n istio-system get ds istio-cni-node >/dev/null 2>&1; then   # where do CNI configs live on this k3d node?
    for d in /etc/cni/net.d /var/lib/rancher/k3s/agent/etc/cni/net.d; do
      echo "--- node $d: $(docker exec k3d-difme-server-0 ls -la "$d" 2>&1 | tail -n +2 | awk '{print $NF}' | tr '\n' ' ')"
    done
  fi
  # Synced+Healthy is required; name what drifts (istio-base/istiod, 2026-11-20).
  echo "== out-of-sync resources (Argo CD view)"
  for a in $(apps | awk '$3 == "OutOfSync" {print $1}'); do
    kubectl -n argocd get application "$a" -o jsonpath='{range .status.resources[?(@.status=="OutOfSync")]}{.kind} {.namespace} {.name}{"\n"}{end}' \
      | head -8 | sed "s/^/  $a: /"
  done
  kubectl get rollouts -A 2>/dev/null | head; kubectl get rs -n core-platform 2>/dev/null | head -15
  echo "== recent warning events"; kubectl get events -A --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -20 | cut -c1-250
fi
echo "== $P: after"; bash ci/mem-report.sh 12
# Process/thread level (owner rule 2026-11-20): every process in every pod, its PSS, threads and thread groups.
# Idle measurements need the startup burst (jar and catalog loading) to pass first (2026-11-20 io pass).
if [ "${PROC_SETTLE:-0}" -gt 0 ] 2>/dev/null; then echo "== settling ${PROC_SETTLE}s before the process report"; sleep "$PROC_SETTLE"; fi
echo "== $P: processes"; sudo -n env "PATH=$PATH" "KUBECONFIG=${KUBECONFIG:-$HOME/.kube/config}" bash ci/proc-report.sh 45 2>/dev/null || bash ci/proc-report.sh 45
# YugabyteDB RPC mix (informational): which RPCs drive the idle tserver<->master traffic (2026-11-20: not heartbeats,
# not tablet count). Two samples of the *_count handler metrics 15 s apart; top methods by calls/s.
if kubectl -n database-tier get pod yb-master-0 >/dev/null 2>&1; then
  echo "== YugabyteDB RPC calls/s (top methods)"
  for t in "yb-master-0:7000" "yb-tserver-0:9000"; do
    u="/api/v1/namespaces/database-tier/pods/$t/proxy/prometheus-metrics"
    kubectl get --raw "$u" 2>/dev/null | grep -E '^handler_latency_.*_count' > /tmp/yb1.txt; sleep 15
    kubectl get --raw "$u" 2>/dev/null | grep -E '^handler_latency_.*_count' > /tmp/yb2.txt
    awk 'FNR==NR {a[$1]=$2; next} ($1 in a) {d=($2-a[$1])/15; if (d>0) printf "%.1f %s\n", d, $1}' /tmp/yb1.txt /tmp/yb2.txt \
      | sed -E 's/handler_latency_//; s/\{[^}]*\}//' | sort -nr | head -6 | sed "s|^|  ${t%%:*}: |"
  done
fi
# Evidence custody smoke: with the security switch present, the published keys must be the transit operator key and the
# notary's own key (local dev keys are named exchange-svc / exchange-witness).
if [ "$rc" -eq 0 ] && kubectl -n core-platform get configmap exchange-evidence >/dev/null 2>&1; then
  echo "== evidence signers"
  kubectl get --raw /api/v1/namespaces/core-platform/services/exchange-svc:8089/proxy/v1/exchange/keys 2>/dev/null \
    | python3 -c "import json,sys; k=json.load(sys.stdin); print('  published keys:', sorted(k)); print('  notary witness:', 'yes' if 'notary-1' in k else 'NO (local witness)')" 2>/dev/null \
    || echo "  (exchange-svc keys endpoint not reachable)"
  # During a rolling update the old pod keeps answering with local keys while a new one may be failing: show both.
  if ! kubectl get --raw /api/v1/namespaces/core-platform/services/exchange-svc:8089/proxy/v1/exchange/keys 2>/dev/null | grep -q notary; then
    kubectl -n core-platform get pods -l app=exchange-svc --no-headers 2>&1 | cut -c1-140
    newest=$(kubectl -n core-platform get pods -l app=exchange-svc --sort-by=.metadata.creationTimestamp -o name 2>/dev/null | tail -1)
    [ -n "$newest" ] && kubectl -n core-platform logs "$newest" --tail=30 2>&1 | grep -iE 'evidence|openbao|notary|transit|error|exception|caused' | tail -12 | cut -c1-260
    echo "  restart job:"; kubectl -n core-platform logs job/exchange-evidence-restart --tail=5 2>&1 | cut -c1-200
    kubectl -n core-platform get events --sort-by=.lastTimestamp 2>/dev/null | grep -iE 'exchange-evidence|exchange-svc' | tail -6 | cut -c1-220
  fi
fi
# Lineage smoke (informational): what has reached Marquez (Trino queries, dbt runs every 15 min, Flink job status).
if [ "$rc" -eq 0 ] && kubectl -n governance get svc marquez >/dev/null 2>&1; then
  echo "== lineage in Marquez"
  # Active smoke: run dbt now (it goes through dbt-ol and Trino, so both emitters fire) instead of waiting for the cron.
  if kubectl -n lakehouse get cronjob dbt-run >/dev/null 2>&1; then
    kubectl -n lakehouse delete job dbt-lineage-smoke --ignore-not-found >/dev/null 2>&1
    if ! out=$(kubectl -n lakehouse create job --from=cronjob/dbt-run dbt-lineage-smoke 2>&1); then
      echo "  dbt run: could not create the job: $out" | cut -c1-300
    elif kubectl -n lakehouse wait --for=condition=complete job/dbt-lineage-smoke --timeout=420s >/dev/null 2>&1; then
      echo "  dbt run: complete"
    else
      echo "  dbt run: not complete"
      kubectl -n lakehouse get pods -l job-name=dbt-lineage-smoke --no-headers 2>&1 | cut -c1-160
      kubectl -n lakehouse logs -l job-name=dbt-lineage-smoke --all-containers --tail=25 2>&1 | cut -c1-220
      kubectl -n lakehouse get events --field-selector involvedObject.kind=Pod --sort-by=.lastTimestamp 2>/dev/null | grep dbt-lineage | tail -5 | cut -c1-220
    fi
    sleep 10
  fi
  m=/api/v1/namespaces/governance/services/marquez:5000/proxy/api/v1
  kubectl get --raw "$m/namespaces" 2>/dev/null | python3 -c "import json,sys; print('  namespaces:', [n['name'] for n in json.load(sys.stdin).get('namespaces',[])])" 2>/dev/null || echo "  (Marquez API not reachable)"
  kubectl get --raw "$m/namespaces/difme/jobs?limit=20" 2>/dev/null | python3 -c "import json,sys; j=json.load(sys.stdin).get('jobs',[]); print('  difme jobs:', len(j), [x['name'][:60] for x in j[:8]])" 2>/dev/null || true
fi
exit $rc
