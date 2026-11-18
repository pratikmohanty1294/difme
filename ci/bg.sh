#!/usr/bin/env bash
# Runs a long job inside the Codespace with no dependency on the client computer:
#   bash ci/bg.sh NAME command args...     → log /tmp/NAME.log, last line "BG_EXIT <code>"
# While the job runs, a keep-alive connects to this Codespace through GitHub every 4 minutes, so the 60-minute idle
# timeout can't stop it midway. When the job ends the keep-alive ends too and the normal idle timeout applies again
# (Codespace hours come out of the monthly allowance, so it never runs on its own).
# One-time setup in the Codespace terminal: gh auth login -s codespace
set -euo pipefail
NAME=$1; shift
LOG=/tmp/$NAME.log
nohup bash -c '"$@"; echo "BG_EXIT $?"' _ "$@" > "$LOG" 2>&1 &
JOB=$!
if [ -n "${CODESPACE_NAME:-}" ] && gh auth status 2>&1 | grep -q codespace; then
  nohup bash -c "while kill -0 $JOB 2>/dev/null; do gh codespace ssh -c '$CODESPACE_NAME' -- true >/dev/null 2>&1; sleep 240; done" \
    > /tmp/$NAME.keepalive.log 2>&1 &
  echo "started $NAME (pid $JOB) with keep-alive; log $LOG"
else
  echo "started $NAME (pid $JOB) WITHOUT keep-alive (run once: gh auth login -s codespace); log $LOG"
fi
