#!/usr/bin/env bash
# Process- and thread-level memory report for every pod on the node (owner rule: optimise memory at process/thread
# level). Reads /proc on the host, so it also sees distroless containers without exec'ing into them; each PID is
# mapped to its pod through the pod UID in its cgroup path.
#   sudo ci/proc-report.sh [top-N]        (root is needed to read other users' smaps_rollup)
#   PROC_REPORT_ALL=1 also lists processes outside pods (host view; used to test the script).
# Columns: PSS (proportional set size: shared pages split between sharers), anonymous PSS (heap, stacks, arenas),
# threads, runtime, and the largest thread groups by name (digits stripped: "GC Thread#3" -> "GC Thread#").
set -uo pipefail
N=${1:-40}
KUBECTL=${KUBECTL:-kubectl}
declare -A POD
while read -r uid ns name; do [ -n "$uid" ] || continue; POD[${uid//-/_}]="$ns/$name"; POD[$uid]="$ns/$name"; done < <(
  $KUBECTL get pods -A -o jsonpath='{range .items[*]}{.metadata.uid} {.metadata.namespace} {.metadata.name}{"\n"}{end}' 2>/dev/null)

runtime() {  # runtime <pid> <comm> <cmdline>
  case "$3" in
    *java*|*/jre/*|*/jdk*) echo jvm ;; *node\ *|node|*/node\ *|*/nodejs*) echo node ;;
    *postgres*|*postmaster*) echo postgres ;; *nginx*) echo nginx ;; *python*|*gunicorn*|*superset*) echo python ;;
    *) if grep -q 'runtime\.goexit\|Go buildinf' "/proc/$1/exe" 2>/dev/null; then echo go; else echo native; fi ;;
  esac
}

rows=$(mktemp)
for d in /proc/[0-9]*; do
  pid=${d#/proc/}
  cg=$(cat "$d/cgroup" 2>/dev/null) || continue
  uid=$(grep -oE 'pod[0-9a-f_-]{36}' <<<"$cg" | head -1 | sed 's/^pod//')
  if [ -z "$uid" ]; then [ "${PROC_REPORT_ALL:-0}" = 1 ] || continue; uid=host; POD[host]=host/-; fi
  pod=${POD[$uid]:-${POD[${uid//-/_}]:-?/pod-$uid}}
  read -r pss anon < <(awk '/^Pss:/{p=$2} /^Pss_Anon:/{a=$2} END{print p+0, a+0}' "$d/smaps_rollup" 2>/dev/null) || continue
  [ "${pss:-0}" -gt 0 ] || continue
  comm=$(cat "$d/comm" 2>/dev/null); cmd=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null | cut -c1-200)
  thr=$(awk '/^Threads:/{print $2}' "$d/status" 2>/dev/null)
  groups=$(cat "$d"/task/*/comm 2>/dev/null | sed -E 's/[0-9]+$//; s/-[0-9]+-?$//' | sort | uniq -c | sort -rn | head -4 \
           | awk '{c=$1; $1=""; sub(/^ /,""); printf "%s×%d, ", $0, c}' | sed 's/, $//')
  printf '%s\t%s\t%s\t%d\t%d\t%s\t%s\n' "$pss" "$pod" "$comm" "$anon" "$thr" "$(runtime "$pid" "$comm" "$cmd")" "$groups" >> "$rows"
done

total=$(awk -F'\t' '{s+=$1} END{print int(s/1024)}' "$rows")
echo "== processes in pods: $(wc -l < "$rows"), PSS total ${total} MiB, threads $(awk -F'\t' '{s+=$5} END{print s}' "$rows")"
echo "== by runtime (PSS MiB / anon MiB / processes / threads)"
awk -F'\t' '{p[$6]+=$1; a[$6]+=$4; n[$6]++; t[$6]+=$5} END {for (r in p) printf "  %-9s %7d %7d %5d %6d\n", r, p[r]/1024, a[r]/1024, n[r], t[r]}' "$rows" | sort -k2 -nr
echo "== top $N processes by PSS"
printf '  %7s %7s %5s  %-8s %-48s %-16s %s\n' PSS_MiB anon thr runtime pod process "thread groups"
sort -t$'\t' -k1,1nr "$rows" | head -"$N" | awk -F'\t' '{printf "  %7d %7d %5d  %-8s %-48s %-16s %s\n", $1/1024, $4/1024, $5, $6, substr($2,1,48), substr($3,1,16), substr($7,1,110)}'
rm -f "$rows"
