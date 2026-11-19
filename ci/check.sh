#!/usr/bin/env bash
# Fast local feedback (seconds to ~1 min) — run in the Codespace before every push. CI confirms; it should not discover.
# Usage: ci/check.sh [core|flink|backoffice|deploy|verticals|api|tla|all] [maven-modules,comma,separated]
set -euo pipefail
cd "$(dirname "$0")/.."
AREA=${1:-all}
MODULES=${2:-}
want() { [ "$AREA" = all ] || [ "$AREA" = "$1" ]; }
t0=$(date +%s)
# Pick a JDK per area: core services target Java 25, Flink 2.x jobs Java 21 (both installed by the devcontainer).
use_jdk() { local d; d=$(ls -d /usr/local/sdkman/candidates/java/"$1"* 2>/dev/null | head -1); [ -n "$d" ] && export JAVA_HOME=$d PATH=$d/bin:$PATH; return 0; }

if want core; then
  # .properties files have no inline comments: "key=8015   # note" keeps the note in the value, and the service
  # fails at startup, which unit tests never reach (cmd-svc crash-looped on difme.flagd.port, 2026-11-19).
  if grep -nE '^[^#!][^=]*=.*[^\]\s+#' services/*/*/src/main/resources/application.properties; then
    echo "✗ inline comment in an application.properties value (put the comment on its own line)"; exit 1
  fi
  use_jdk 25
  pl=${MODULES:+-pl $MODULES -am}
  mvn -q -B -ntp -f services/core/pom.xml $pl verify
  echo "✓ core"
fi
if want flink; then
  use_jdk 21
  mvn -q -B -ntp -f analytics/flink-jobs/pom.xml verify
  echo "✓ flink"
fi
if want backoffice; then
  (cd backoffice && pnpm install --frozen-lockfile --prefer-offline --silent && pnpm turbo run build --output-logs=errors-only && pnpm bundle-budget)
  echo "✓ backoffice"
fi
if want deploy; then
  python3 deploy/argocd/render.py --check
  # Generated service manifests must match services.yaml (a stale file shipped once; found 2026-11-18).
  python3 deploy/components/quarkus-service/render.py >/dev/null
  git diff --exit-code --stat -- deploy/platform/core-platform/ || { echo "✗ generated manifests are stale: commit the re-rendered files"; exit 1; }
  find deploy/platform -name kustomization.yaml -print0 | while IFS= read -r -d '' k; do
    kustomize build --load-restrictor LoadRestrictionsNone "$(dirname "$k")" >/dev/null || { echo "✗ $k"; exit 1; }
  done
  echo "✓ deploy"
fi
if want verticals; then
  python3 verticals/check.py
fi
if want api; then
  (cd api/proto && buf lint)
  spectral lint -q --ruleset api/openapi/.spectral.yaml api/openapi/difme.openapi.yaml
  echo "✓ api"
fi
if want tla; then
  TLA_JAR=/opt/tla/tla2tools.jar bash specs/tla/check.sh >/dev/null
  echo "✓ tla"
fi
echo "check $AREA passed in $(( $(date +%s) - t0 ))s"
