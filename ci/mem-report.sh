#!/usr/bin/env bash
# One-shot footprint report for the Codespace cluster (used for every before/after measurement).
#   ci/mem-report.sh [top-N]
# Headroom matters because the Codespaces host runs earlyoom: it SIGTERMs the largest process once available RAM
# and free swap both drop below ~10% (docs/knowledge-base/cluster.md).
set -uo pipefail
N=${1:-12}
read -r total avail < <(free -m | awk '/Mem:/ {print $2, $7}')
read -r stotal sused < <(free -m | awk '/Swap:/ {print $2, $3}')
echo "== memory: available ${avail} MiB of ${total} (earlyoom line ~$((total / 10)) MiB) | swap ${sused}/${stotal} MiB"
Z=/sys/kernel/debug/zswap
mountpoint -q /sys/kernel/debug 2>/dev/null || sudo mount -t debugfs none /sys/kernel/debug 2>/dev/null
if [ -r $Z/stored_pages ]; then
  pool=$(( $(sudo cat $Z/pool_total_size) / 1048576 )); stored=$(( $(sudo cat $Z/stored_pages) * 4 / 1024 ))
  echo "== zswap: ${stored} MiB stored in ${pool} MiB ($([ $pool -gt 0 ] && echo "$((stored / pool)):1" || echo n/a)); written back to disk: $(( $(sudo cat $Z/written_back_pages) * 4 / 1024 )) MiB"
fi
echo "== node: $(kubectl top node --no-headers 2>/dev/null | awk '{print $2" CPU ("$3"), "$4" RAM ("$5")"}')"
echo "== requests: $(kubectl describe node 2>/dev/null | grep -A4 'Allocated resources' | awk '/cpu|memory/ {printf "%s %s %s  ", $1, $2, $3}')"
echo "== by namespace (Mi RAM / m CPU)"
kubectl top pods -A --no-headers 2>/dev/null | awk '{m[$1]+=$4; c[$1]+=$3} END {for (n in m) printf "  %-16s %6d Mi %6d m\n", n, m[n], c[n]}' | sort -k2 -n -r
echo "== top $N pods by RAM"
kubectl top pods -A --no-headers 2>/dev/null | sort -k4 -h -r | head -"$N" | awk '{printf "  %-15s %-55s %6s %6s\n", $1, $2, $4, $3}'
