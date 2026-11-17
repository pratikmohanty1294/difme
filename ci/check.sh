#!/usr/bin/env bash
# Fast local feedback (seconds to ~1 min) — run in the Codespace before every push. CI confirms; it should not discover.
# Usage: ci/check.sh [core|flink|backoffice|deploy|api|tla|all] [maven-modules,comma,separated]
set -euo pipefail
cd "$(dirname "$0")/.."
AREA=${1:-all}
MODULES=${2:-}
want() { [ "$AREA" = all ] || [ "$AREA" = "$1" ]; }
t0=$(date +%s)

if want core; then
  pl=${MODULES:+-pl $MODULES -am}
  mvn -q -B -ntp -f services/core/pom.xml $pl verify
  echo "✓ core"
fi
if want flink; then
  mvn -q -B -ntp -f analytics/flink-jobs/pom.xml verify
  echo "✓ flink"
fi
if want backoffice; then
  (cd backoffice && pnpm install --prefer-offline --silent && pnpm turbo run build --output-logs=errors-only && pnpm bundle-budget)
  echo "✓ backoffice"
fi
if want deploy; then
  python3 deploy/argocd/render.py --check
  find deploy/platform -name kustomization.yaml -print0 | while IFS= read -r -d '' k; do
    kustomize build --load-restrictor LoadRestrictionsNone "$(dirname "$k")" >/dev/null || { echo "✗ $k"; exit 1; }
  done
  echo "✓ deploy"
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
