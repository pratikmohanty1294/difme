#!/usr/bin/env bash
# Footprint + throughput baseline (fast-dev-loop rule: measure, don't assume). Run inside the Codespace:
#   bash ci/measure.sh [RATE] [DURATION]        e.g. bash ci/measure.sh 200 60s
# Prints: idle CPU/RAM per pod and node, k6 latency/throughput/outcomes, peak CPU/RAM per pod under load.
set -euo pipefail
cd "$(dirname "$0")/.."
RATE=${1:-200}; DURATION=${2:-60s}; ACCOUNTS=${ACCOUNTS:-1000}
K6_IMAGE=grafana/k6:1.8.2
OUT=/tmp/difme-measure; mkdir -p "$OUT"

echo "▶ seeding $ACCOUNTS ledger accounts (idempotent: existing ids are reported as 'exists')"
# Batches of 100: TigerBeetle --development (dev sizing) shrinks the batch size, and its repl client panics
# ("reached unreachable code") on a 254-account batch; 200 works (measured 2026-11-20). 100 is safe for both sizings.
for start in $(seq 1 100 "$ACCOUNTS"); do
  end=$(( start + 99 < ACCOUNTS ? start + 99 : ACCOUNTS ))
  # (A `[ ] && printf` loop here aborted the script under set -e on its last iteration — found live.)
  cmd=$(seq -f 'id=%g code=10 ledger=1' "$start" "$end" | paste -sd, - | sed 's/,/, /g')
  kubectl -n database-tier exec tigerbeetle-0 -c tigerbeetle -- /tigerbeetle repl --cluster=0 --addresses=3000 \
    --command="create_accounts $cmd;" >/dev/null
done

# Keyed by workload (Deployment pod-template hash and suffix stripped; mawk has no {n,m}, hence split), so a pod restarted mid-run still lines up with its idle sample.
top_pods() { kubectl top pods -A --no-headers | grep -vE "^(kube-system|argocd|argo-rollouts) " | awk '{ n=split($2, p, "-"); w=$2; if (n > 2 && length(p[n]) == 5 && length(p[n-1]) >= 8) { w=p[1]; for (i=2; i<=n-2; i++) w=w"-"p[i] } print $1, w, $3, $4 }' | sort -k1,2; }

echo "▶ waiting for the node to settle (CPU < 50 % twice in a row, max 5 min)"
# The first baseline sampled "idle" 30 s after a restart and recorded JVM start-up as idle load (found 2026-11-18).
calm=0; for _ in $(seq 1 30); do
  pct=$(kubectl top node --no-headers | awk '{ sub("%", "", $3); print $3 }')
  if [ "${pct:-100}" -lt 50 ]; then calm=$((calm + 1)); else calm=0; fi
  [ $calm -ge 2 ] && break; sleep 10
done
echo "▶ idle snapshot"
top_pods > "$OUT/idle.txt"; kubectl top node --no-headers > "$OUT/idle-node.txt"

echo "▶ load: $RATE req/s for $DURATION (+15 s warm-up), open model"
kubectl -n k6 delete job payments-baseline --ignore-not-found >/dev/null
kubectl -n k6 create configmap perf-scripts --from-file=tests/perf/payments.js --dry-run=client -o yaml | kubectl apply -f - >/dev/null
cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: { name: payments-baseline, namespace: k6 }
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 3600
  template:
    metadata: { labels: { difme/team: platform, business.metric: payment-processing } }
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: $K6_IMAGE
          args: [run, --quiet, /scripts/payments.js]
          env: [{ name: RATE, value: "$RATE" }, { name: DURATION, value: "$DURATION" }, { name: ACCOUNTS, value: "$ACCOUNTS" }]
          volumeMounts: [{ name: scripts, mountPath: /scripts }]
          # Small request: with all profiles up the node's CPU requests are ~99% reserved and a 500m request never
          # schedules (found live). Requests only gate scheduling; k6 still bursts to what it needs.
          resources: { requests: { cpu: 10m, memory: 256Mi }, limits: { memory: 1Gi } }
      volumes: [{ name: scripts, configMap: { name: perf-scripts } }]
EOF
# `kubectl wait -l` fails at once when no pod exists yet (found live), so wait for the pod to appear.
timeout 180 sh -c "until kubectl -n k6 get pods -l job-name=payments-baseline -o name | grep -q .; do sleep 2; done"

: > "$OUT/load.txt"; : > "$OUT/load-node.txt"
# Fail fast instead of waiting forever on an unschedulable pod.
if ! timeout 120 sh -c "until kubectl -n k6 get pods -l job-name=payments-baseline -o jsonpath='{.items[0].status.phase}' | grep -qE 'Running|Succeeded|Failed'; do sleep 3; done"; then
  echo "✗ k6 pod did not start within 120 s:"; kubectl -n k6 describe pods -l job-name=payments-baseline | grep -A4 '^Events' | tail -3; exit 1
fi
deadline=$(( $(date +%s) + 600 ))
while ! kubectl -n k6 get job payments-baseline -o jsonpath='{.status.conditions[*].type}' | grep -qE 'Complete|Failed'; do
  [ "$(date +%s)" -gt "$deadline" ] && { echo "✗ k6 job did not finish within 10 min"; exit 1; }
  top_pods >> "$OUT/load.txt" 2>/dev/null || true
  kubectl top node --no-headers >> "$OUT/load-node.txt" 2>/dev/null || true
  sleep 10
done

echo; echo "════════ RESULTS ($RATE req/s, $DURATION) ════════"
kubectl -n k6 logs job/payments-baseline | grep -E "http_req_duration|http_reqs|http_req_failed|payment_outcomes|checks|iterations|dropped_iterations|steady|status:" | sed 's/^ *//'
echo; echo "── per-pod CPU (m) / RAM (Mi): idle → peak under load ──"
awk 'NR==FNR { cpu[$1"/"$2]=$3; mem[$1"/"$2]=$4; next }
     { k=$1"/"$2; c=$3+0; m=$4+0; if (c>pc[k]) pc[k]=c; if (m>pm[k]) pm[k]=m }
     END { for (k in cpu) printf "%-60s %6s → %5dm   %7s → %5dMi\n", k, cpu[k], pc[k], mem[k], pm[k] }' \
     "$OUT/idle.txt" "$OUT/load.txt" | sort -t'>' -k3 -rn | head -25
echo; echo "── node: idle → peak ──"
cat "$OUT/idle-node.txt"; sort -k2 -rn "$OUT/load-node.txt" | head -1
echo; echo "── restarts during the run ──"
kubectl get pods -A --no-headers | awk '$5+0 > 0 { print $1"/"$2, "restarts="$5 }' | grep -vE "^(kube-system|argocd)" || echo none
