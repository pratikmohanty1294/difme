#!/usr/bin/env bash
# Process- and thread-level resource report for every pod on the node (owner rules: optimise memory, then CPU, then
# disk I/O, then network I/O, at process/thread level). Reads /proc on the host, so it also sees distroless containers
# without exec'ing into them; each PID is mapped to its pod through the pod UID in its cgroup path.
#   sudo ci/proc-report.sh [top-N]          (root is needed to read other users' smaps_rollup and io)
#   PROC_WINDOW=15                          seconds between the two samples that rates are computed over
#   PROC_REPORT_ALL=1                       also list processes outside pods (host view; used to test the script)
# Memory is PSS (shared pages split between sharers) and anonymous PSS (heap, stacks, malloc arenas). CPU, disk and
# network are rates over the sampling window, so start-up work doesn't count as steady-state cost.
set -uo pipefail
N=${1:-40}
WINDOW=${PROC_WINDOW:-15}
KUBECTL=${KUBECTL:-kubectl}
HZ=$(getconf CLK_TCK 2>/dev/null || echo 100)
declare -A POD PODS PODNET CGCACHE
while read -r uid ns name; do [ -n "$uid" ] || continue; POD[$uid]="$ns/$name"; POD[${uid//-/_}]="$ns/$name"; done < <(
  $KUBECTL get pods -A -o jsonpath='{range .items[*]}{.metadata.uid} {.metadata.namespace} {.metadata.name}{"\n"}{end}' 2>/dev/null)

podof() {  # podof <pid> -> ns/pod, or nothing when the process is not in a pod
  local uid; uid=$(grep -oE 'pod[0-9a-f_-]{36}' "/proc/$1/cgroup" 2>/dev/null | head -1 | sed 's/^pod//')
  if [ -z "$uid" ]; then [ "${PROC_REPORT_ALL:-0}" = 1 ] && echo "host/-"; return 0; fi
  echo "${POD[$uid]:-?/pod-$uid}"
}
runtime() {  # runtime <pid> <cmdline>
  case "$2" in
    *java*|*/jre/*|*/jdk*) echo jvm ;; node\ *|*/node\ *|*/nodejs*) echo node ;;
    *postgres*|*postmaster*) echo postgres ;; *nginx*) echo nginx ;; *python*|*gunicorn*|*superset*) echo python ;;
    *) if grep -q 'runtime\.goexit\|Go buildinf' "/proc/$1/exe" 2>/dev/null; then echo go; else echo native; fi ;;
  esac
}
sample() {  # sample <file>: "T pid tid ticks group" per thread, "P pid read_bytes write_bytes" per process
  : > "$1"
  local p t s rest
  for p in "${!PODS[@]}"; do
    for t in /proc/"$p"/task/[0-9]*; do
      s=$(cat "$t/stat" 2>/dev/null) || continue
      rest=${s##*) }; read -r -a f <<<"$rest"           # after the comm: f[11]=utime, f[12]=stime
      printf 'T %s %s %s %s\n' "$p" "${t##*/}" $(( ${f[11]:-0} + ${f[12]:-0} )) \
        "$(sed -E 's/[0-9]+$//; s/-[0-9]+-?$//; s/[ ]+/_/g' "$t/comm" 2>/dev/null)" >> "$1"
    done
    printf 'P %s %s\n' "$p" "$(awk '/^read_bytes/{r=$2} /^write_bytes/{w=$2} END{print r+0, w+0}' "/proc/$p/io" 2>/dev/null)" >> "$1"
  done
}
netsample() {  # netsample <file>: "ns/pod rx tx", read from one process per pod (a pod shares one network namespace)
  : > "$1"
  local pod
  for pod in "${!PODNET[@]}"; do
    awk -v pod="$pod" 'NR>2 { sub(/:/, " "); if ($1 != "lo") { rx+=$2; tx+=$10 } } END { print pod, rx+0, tx+0 }' \
      "/proc/${PODNET[$pod]}/net/dev" 2>/dev/null >> "$1"
  done
}

cgsample() {  # cgsample <file>: "ns/pod refaulted_file_pages current_bytes max" from each pod's cgroup (found by pod UID)
  : > "$1"
  local pod uid cg
  for pod in "${!PODNET[@]}"; do
    uid=$(grep -oE 'pod[0-9a-f_-]{36}' "/proc/${PODNET[$pod]}/cgroup" 2>/dev/null | head -1)
    [ -n "$uid" ] || continue
    # /proc/<pid>/cgroup paths are relative to the k3d node's cgroup namespace, so they don't resolve from the runner
    # host (the first version printed nothing); search the host hierarchy for the pod directory instead.
    cg=${CGCACHE[$uid]:-}
    [ -n "$cg" ] || { cg=$(find /sys/fs/cgroup -maxdepth 8 -type d -name "*${uid}*" 2>/dev/null | awk '{ print length, $0 }' | sort -n | head -1 | cut -d' ' -f2-); CGCACHE[$uid]=$cg; }
    [ -n "$cg" ] && [ -r "$cg/memory.stat" ] || continue
    printf '%s %s %s %s\n' "$pod" "$(awk '/^workingset_refault_file /{print $2}' "$cg/memory.stat")" \
      "$(cat "$cg/memory.current" 2>/dev/null || echo 0)" "$(cat "$cg/memory.max" 2>/dev/null || echo max)" >> "$1"
  done
}

for d in /proc/[0-9]*; do
  p=${d#/proc/}; pod=$(podof "$p"); [ -n "$pod" ] || continue
  PODS[$p]=$pod; [ -n "${PODNET[$pod]:-}" ] || PODNET[$pod]=$p
done
rows=$(mktemp); s1=$(mktemp); s2=$(mktemp); n1=$(mktemp); n2=$(mktemp); c1=$(mktemp); c2=$(mktemp)
sample "$s1"; netsample "$n1"; cgsample "$c1"; sleep "$WINDOW"; sample "$s2"; netsample "$n2"; cgsample "$c2"

# Per-process CPU %, disk KB/s and the three CPU-heaviest thread groups over the window (one awk pass for all).
awk -v hz="$HZ" -v w="$WINDOW" '
  FNR==NR { if ($1=="T") t1[$2" "$3]=$4; else if ($1=="P") { r1[$2]=$3; w1[$2]=$4 }; next }
  $1=="T" { k=$2" "$3; d=$4-((k in t1)?t1[k]:$4); cpu[$2]+=d; g[$2"\t"$5]+=d; n[$2"\t"$5]++; next }
  $1=="P" { rd[$2]=($3-r1[$2])/1024/w; wr[$2]=($4-w1[$2])/1024/w }
  END {
    for (k in g) { split(k, a, "\t"); line[a[1]]=line[a[1]] sprintf("%012d %s×%d=%.1f%%;", g[k], a[2], n[k], 100*g[k]/hz/w) }
    for (p in rd) printf "%s\t%.1f\t%.1f\t%.1f\t%s\n", p, 100*cpu[p]/hz/w, rd[p], wr[p], line[p]
  }' "$s1" "$s2" > "$rows.rate"

while IFS=$'\t' read -r p cpu rd wr glist; do
  [ -r "/proc/$p/smaps_rollup" ] || continue
  read -r pss anon < <(awk '/^Pss:/{a=$2} /^Pss_Anon:/{b=$2} END{print a+0, b+0}' "/proc/$p/smaps_rollup" 2>/dev/null)
  [ "${pss:-0}" -gt 0 ] || continue
  thr=$(awk '/^Threads:/{print $2}' "/proc/$p/status" 2>/dev/null)
  groups=$(tr ';' '\n' <<<"$glist" | grep -v '^$' | sort -r | head -3 | cut -d' ' -f2- | paste -sd, - | sed 's/,/, /g')
  cmd=$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | cut -c1-200)
  printf '%s\t%s\t%s\t%d\t%d\t%s\t%s\t%s\t%s\t%s\n' "$pss" "${PODS[$p]}" "$(cat "/proc/$p/comm" 2>/dev/null)" "$anon" "${thr:-0}" \
    "$(runtime "$p" "$cmd")" "$cpu" "$rd" "$wr" "$groups" >> "$rows"
done < "$rows.rate"

# Columns in $rows: 1 PSS kB, 2 pod, 3 process, 4 anon kB, 5 threads, 6 runtime, 7 CPU %, 8 read KB/s, 9 write KB/s, 10 groups
echo "== processes in pods: $(wc -l < "$rows"), PSS $(awk -F'\t' '{s+=$1} END{print int(s/1024)}' "$rows") MiB, threads $(awk -F'\t' '{s+=$5} END{print s}' "$rows"), CPU $(awk -F'\t' '{s+=$7} END{printf "%.0f", s}' "$rows")% of one core (window ${WINDOW}s)"
echo "== by runtime (PSS MiB / anon MiB / processes / threads / CPU % / disk read+write KB/s)"
awk -F'\t' '{p[$6]+=$1; a[$6]+=$4; n[$6]++; t[$6]+=$5; c[$6]+=$7; io[$6]+=$8+$9}
  END {for (r in p) printf "  %-9s %7d %7d %5d %6d %7.1f %9.1f\n", r, p[r]/1024, a[r]/1024, n[r], t[r], c[r], io[r]}' "$rows" | sort -k2 -nr
echo "== top $N processes by PSS"
printf '  %7s %6s %4s %6s %8s %8s  %-8s %-44s %-15s %s\n' PSS_MiB anon thr CPU% rd_KB/s wr_KB/s runtime pod process "busiest thread groups (CPU %)"
sort -t$'\t' -k1,1nr "$rows" | head -"$N" | awk -F'\t' '{printf "  %7d %6d %4d %6.1f %8.1f %8.1f  %-8s %-44s %-15s %s\n", $1/1024, $4/1024, $5, $7, $8, $9, $6, substr($2,1,44), substr($3,1,15), substr($10,1,90)}'
echo "== top 10 by CPU over the window"
sort -t$'\t' -k7,7nr "$rows" | head -10 | awk -F'\t' '{printf "  %6.1f%%  %-44s %-15s %s\n", $7, substr($2,1,44), substr($3,1,15), substr($10,1,80)}'
# A 15 s window catches bursts (job start, savepoint upload); the average since process start shows the steady cost
# (fraud-cep read 4.2 MB/s in one window and 0.2 MB/s in another, 2026-11-20).
echo "== top 10 by disk I/O averaged since process start (read+write KB/s, process age)"
up=$(awk '{print int($1)}' /proc/uptime)
for p in "${!PODS[@]}"; do
  st=$(awk '{print $22}' "/proc/$p/stat" 2>/dev/null) || continue; [ -n "$st" ] || continue
  age=$(( up - st / HZ )); [ "$age" -gt 30 ] || continue
  awk -v age="$age" -v pod="${PODS[$p]}" -v c="$(cat /proc/$p/comm 2>/dev/null)" '/^read_bytes/{r=$2} /^write_bytes/{w=$2} END{printf "%.1f\t%.1f\t%.1f\t%d\t%s\t%s\n", (r+w)/1024/age, r/1024/age, w/1024/age, age, pod, c}' "/proc/$p/io" 2>/dev/null
done | sort -t$'\t' -k1,1nr | head -10 | awk -F'\t' '$1>0 {printf "  %8.1f (r %.1f w %.1f, %ds)  %-44s %s\n", $1, $2, $3, $4, substr($5,1,44), $6}'
echo "== top 10 by disk I/O (read+write KB/s)"
awk -F'\t' '{printf "%.1f\t%s\t%s\t%s\t%s\n", $8+$9, $8, $9, $2, $3}' "$rows" | sort -t$'\t' -k1,1nr | head -10 \
  | awk -F'\t' '$1>0 {printf "  %8.1f (r %.1f w %.1f)  %-44s %s\n", $1, $2, $3, substr($4,1,44), $5}'
# Disk reads at idle are often page-cache thrash: a container near its memory limit drops file pages (jar, AOT cache,
# binary text) and reads them back. Refaults/s > 0 with current near max confirms it (added 2026-11-20, cmd-svc).
echo "== top 10 containers by file refaults/s (memory current / limit MiB)"
awk -v w="$WINDOW" 'FNR==NR {r[$1]=$2; next} ($1 in r) {d=($2-r[$1])/w; m=($4=="max")?"none":int($4/1048576); printf "%.1f %s %d %s\n", d, $1, $3/1048576, m}' "$c1" "$c2" \
  | sort -k1,1nr | head -10 | awk '$1>0 {printf "  %8.1f pages/s  %-44s %d / %s\n", $1, $2, $3, $4}'
echo "== top 10 pods by network (rx+tx KB/s, loopback excluded)"
awk -v w="$WINDOW" 'FNR==NR {r[$1]=$2; t[$1]=$3; next} ($1 in r) {rx=($2-r[$1])/1024/w; tx=($3-t[$1])/1024/w; printf "%.1f %.1f %.1f %s\n", rx+tx, rx, tx, $1}' "$n1" "$n2" \
  | sort -k1,1nr | head -10 | awk '$1>0 {printf "  %8.1f (rx %.1f tx %.1f)  %s\n", $1, $2, $3, $4}'
rm -f "$rows" "$rows.rate" "$s1" "$s2" "$n1" "$n2" "$c1" "$c2"
