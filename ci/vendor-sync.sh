#!/usr/bin/env bash
# Re-vendors upstream source at a pinned tag (ADR-008). Usage: ci/vendor-sync.sh formio-server v4.11.0
set -euo pipefail
what=$1 tag=$2
case "$what" in
  formio-server) repo=formio/formio;    dest=vendor/formio/server ;;
  formio-js)     repo=formio/formio.js; dest=vendor/formio/js ;;
  *) echo "unknown target $what"; exit 1 ;;
esac
sha=$(gh api "repos/$repo/commits/$tag" --jq .sha)
tmp=$(mktemp -d)
gh api "repos/$repo/tarball/$tag" > "$tmp/src.tgz"
rm -rf "$dest" && mkdir -p "$dest"
tar -xzf "$tmp/src.tgz" -C "$dest" --strip-components=1
# Drop upstream CI/test fixtures that are not needed to build or run.
rm -rf "$dest/.github" "$dest/.circleci" "$dest/CLAUDE.md" "$dest/AGENTS.md" "$dest/.claude"
${PYTHON:-python3} - "$what" "$repo" "$tag" "$sha" <<'PY'
import json, sys, pathlib
what, repo, tag, sha = sys.argv[1:]
p = pathlib.Path("vendor/VENDORED.json")
data = json.loads(p.read_text()) if p.exists() else {}
data[what] = {"upstream": f"https://github.com/{repo}", "tag": tag, "commit": sha}
p.write_text(json.dumps(data, indent=2) + "\n")
PY
echo "vendored $repo@$tag ($sha) into $dest"
