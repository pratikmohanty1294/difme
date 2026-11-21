#!/usr/bin/env bash
# Re-vendors upstream source at a pinned tag (ADR-008; owner 2026-11-21: copy outside projects into vendor/ and build
# from there). Usage: ci/vendor-sync.sh <target> <tag>, e.g. ci/vendor-sync.sh hyperswitch v1.129.0
# A shallow git clone of the tag (no history), minus upstream CI, assistant files and non-build material, recorded in
# vendor/VENDORED.json. Local changes to vendored code go in vendor/<target>/PATCHES.md, never silently.
set -euo pipefail
what=$1 tag=$2
prune=()
case "$what" in
  formio-server) repo=formio/formio;      dest=vendor/formio/server ;;
  formio-js)     repo=formio/formio.js;   dest=vendor/formio/js ;;
  # PSP (PA/PG) simulator. Docs, Postman/Cypress suites and the API reference aren't part of the Cargo workspace.
  hyperswitch)   repo=juspay/hyperswitch; dest=vendor/hyperswitch; prune=(docs postman cypress-tests api-reference) ;;
  # Core banking (CBS). Kept whole: its test modules are wired into the Gradle build.
  fineract)      repo=apache/fineract;    dest=vendor/fineract ;;
  # ISO 8583 library for the scheme simulator and adapter (services/scheme-sim).
  moov-iso8583)  repo=moov-io/iso8583;    dest=vendor/moov-iso8583 ;;
  *) echo "unknown target $what"; exit 1 ;;
esac
tmp=$(mktemp -d)
git clone -q --depth 1 --branch "$tag" "https://github.com/$repo.git" "$tmp/src"
sha=$(git -C "$tmp/src" rev-parse HEAD)
rm -rf "$dest" && mkdir -p "$dest"
(cd "$tmp/src" && tar --exclude=.git -cf - .) | tar -xf - -C "$dest"
rm -rf "$dest/.github" "$dest/.circleci" "$dest/CLAUDE.md" "$dest/AGENTS.md" "$dest/.claude" "$dest/.cursor" "$dest/.devcontainer"
find "$dest" -name CLAUDE.md -o -name AGENTS.md | xargs -r rm -f
for p in "${prune[@]}"; do rm -rf "${dest:?}/$p"; done
rm -rf "$tmp"
${PYTHON:-python3} - "$what" "$repo" "$tag" "$sha" "${prune[*]:-}" <<'PY'
import json, sys, pathlib
what, repo, tag, sha, prune = sys.argv[1:]
p = pathlib.Path("vendor/VENDORED.json")
data = json.loads(p.read_text()) if p.exists() else {}
entry = {"upstream": f"https://github.com/{repo}", "tag": tag, "commit": sha}
if prune.strip(): entry["omitted"] = prune.split()
data[what] = entry
p.write_text(json.dumps(data, indent=2) + "\n")
PY
echo "vendored $repo@$tag ($sha) into $dest"
