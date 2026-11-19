#!/usr/bin/env bash
# Runs a long job inside the Codespace with no dependency on the client computer:
#   bash ci/bg.sh NAME command args...     → log /tmp/NAME.log, last line "BG_EXIT <code>"
# While the job runs, a keep-alive connects to this Codespace through GitHub every 4 minutes, so the 60-minute idle
# timeout can't stop it midway. When the job ends the keep-alive ends too and the normal idle timeout applies again
# (Codespace hours come out of the monthly allowance, so it never runs on its own).
# One-time setup: a token with the codespace scope, as the GH_TOKEN Codespaces secret (survives rebuilds), in GH_TOKEN,
# or in ~/.config/difme/gh-token (mode 600). `gh auth login`
# also demands read:org; GH_TOKEN skips that check.
set -euo pipefail
NAME=$1; shift
LOG=/tmp/$NAME.log
nohup bash -c '"$@"; echo "BG_EXIT $?"' _ "$@" > "$LOG" 2>&1 &
JOB=$!
[ -z "${GH_TOKEN:-}" ] && [ -r ~/.config/difme/gh-token ] && GH_TOKEN=$(cat ~/.config/difme/gh-token) && export GH_TOKEN
# A Codespaces secret named GH_TOKEN survives rebuilds; SSH sessions don't load it, so read it from the shared env file.
if [ -z "${GH_TOKEN:-}" ] && [ -r /workspaces/.codespaces/shared/.env ]; then
  GH_TOKEN=$(sed -n 's/^GH_TOKEN=//p' /workspaces/.codespaces/shared/.env | head -1 | tr -d '\r' | sed 's/^"//; s/"$//'); [ -n "$GH_TOKEN" ] && export GH_TOKEN
fi
unset GITHUB_TOKEN   # the Codespace default token lacks the codespace scope and would win otherwise
# SSH sessions don't export CODESPACE_NAME; the Codespace records it here.
CODESPACE_NAME=${CODESPACE_NAME:-$(python3 -c 'import json; print(json.load(open("/workspaces/.codespaces/shared/environment-variables.json")).get("CODESPACE_NAME", ""))' 2>/dev/null || true)}
if [ -n "${CODESPACE_NAME:-}" ] && gh codespace list >/dev/null 2>&1; then
  nohup bash -c "while kill -0 $JOB 2>/dev/null; do gh codespace ssh -c '$CODESPACE_NAME' -- true >/dev/null 2>&1; sleep 240; done" \
    > /tmp/$NAME.keepalive.log 2>&1 &
  echo "started $NAME (pid $JOB) with keep-alive; log $LOG"
else
  echo "started $NAME (pid $JOB) WITHOUT keep-alive (no codespace-scoped token: GH_TOKEN, ~/.config/difme/gh-token or the GH_TOKEN Codespaces secret); log $LOG"
fi
