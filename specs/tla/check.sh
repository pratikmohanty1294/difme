#!/usr/bin/env bash
# Model-check every spec; exits non-zero on any violation (CI gate).
set -euo pipefail
JAR=${TLA_JAR:-/opt/tla/tla2tools.jar}
cd "$(dirname "$0")"
status=0
for cfg in *.cfg; do
  spec="${cfg%.cfg}.tla"
  echo "▶ TLC $spec"
  if ! java -XX:+UseParallelGC -cp "$JAR" tlc2.TLC -workers auto -deadlock -config "$cfg" "$spec"; then
    echo "✗ $spec failed"; status=1
  fi
done
exit $status
