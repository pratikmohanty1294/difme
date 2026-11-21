#!/usr/bin/env bash
# Re-vendors upstream source at a pinned tag (ADR-008; owner 2026-11-21: copy outside projects into vendor/ and build
# from there). Usage: ci/vendor-sync.sh <target> <tag|commit>, e.g. ci/vendor-sync.sh hyperswitch v1.129.0
# (a 40-hex commit pins repos without release tags).
# A shallow git clone of the tag (no history), minus upstream CI, assistant files and non-build material, recorded in
# vendor/VENDORED.json. Local changes to vendored code go in vendor/<target>/PATCHES.md, never silently.
set -euo pipefail
what=$1 tag=$2
prune=() keep=""
case "$what" in
  formio-server) repo=formio/formio;      dest=vendor/formio/server ;;
  formio-js)     repo=formio/formio.js;   dest=vendor/formio/js ;;
  # PSP (PA/PG) simulator. Docs, Postman/Cypress suites and the API reference aren't part of the Cargo workspace.
  hyperswitch)   repo=juspay/hyperswitch; dest=vendor/hyperswitch; prune=(docs postman cypress-tests api-reference) ;;
  # Core banking (CBS). Kept whole: its test modules are wired into the Gradle build.
  fineract)      repo=apache/fineract;    dest=vendor/fineract ;;
  # ISO 8583 library for the scheme simulator and adapter (services/scheme-sim).
  moov-iso8583)  repo=moov-io/iso8583;    dest=vendor/moov-iso8583 ;;
  # Mifos Payment Hub EE (MPL-2.0; owner 2026-11-21: reuse it, with Fluxnova in place of Zeebe).
  ph-ee-common)    repo=openMF/ph-ee-connector-common;    dest=vendor/mifos-ph-ee/connector-common ;;
  ph-ee-channel)   repo=openMF/ph-ee-connector-channel;   dest=vendor/mifos-ph-ee/connector-channel ;;
  ph-ee-ams-mifos) repo=openMF/ph-ee-connector-ams-mifos; dest=vendor/mifos-ph-ee/connector-ams-mifos ;;
  # The BPMN flows only; the rest of env-labs is their own cluster's Helm/env config.
  ph-ee-flows)     repo=openMF/ph-ee-env-labs;            dest=vendor/mifos-ph-ee/flows; keep=orchestration/feel ;;
  *) echo "unknown target $what"; exit 1 ;;
esac
tmp=$(mktemp -d)
if [[ "$tag" =~ ^[0-9a-f]{40}$ ]]; then
  git init -q "$tmp/src" && git -C "$tmp/src" fetch -q --depth 1 "https://github.com/$repo.git" "$tag" && git -C "$tmp/src" checkout -q FETCH_HEAD
else
  git clone -q --depth 1 --branch "$tag" "https://github.com/$repo.git" "$tmp/src"
fi
sha=$(git -C "$tmp/src" rev-parse HEAD)
rm -rf "$dest" && mkdir -p "$dest"
(cd "$tmp/src/$keep" && tar --exclude=.git -cf - .) | tar -xf - -C "$dest"
[ -z "$keep" ] || cp "$tmp/src/LICENSE" "$dest/LICENSE"
rm -rf "$dest/.github" "$dest/.circleci" "$dest/CLAUDE.md" "$dest/AGENTS.md" "$dest/.claude" "$dest/.cursor" "$dest/.devcontainer"
find "$dest" -name CLAUDE.md -o -name AGENTS.md | xargs -r rm -f
# Private keys (upstream dev TLS stores and the like) are never committed, wherever they sit in the tree.
find "$dest" \( -name '*.jks' -o -name '*.p12' -o -name '*.pfx' -o -name '*.key' \) -print -delete
for p in "${prune[@]}"; do rm -rf "${dest:?}/$p"; done
rm -rf "$tmp"
${PYTHON:-python3} - "$what" "$repo" "$tag" "$sha" "${prune[*]:-}" "$keep" <<'PY'
import json, sys, pathlib
what, repo, tag, sha, prune, keep = sys.argv[1:]
p = pathlib.Path("vendor/VENDORED.json")
data = json.loads(p.read_text()) if p.exists() else {}
entry = {"upstream": f"https://github.com/{repo}", "tag": tag, "commit": sha}
if tag == sha: entry["tag"] = "(no release tag; pinned by commit)"
if prune.strip(): entry["omitted"] = prune.split()
if keep: entry["kept_only"] = keep
data[what] = entry
p.write_text(json.dumps(data, indent=2) + "\n")
PY
echo "vendored $repo@$tag ($sha) into $dest"
