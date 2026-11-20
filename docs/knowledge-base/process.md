# How we work: fast feedback loop

1. **Verify before pushing.**
   - `make check AREA=<core|flink|backoffice|deploy|api|tla>` runs in the Codespace in seconds to about a minute.
   - For manifests, also run a server dry run: `kustomize build … | kubectl apply --server-side --force-conflicts --dry-run=server -f -`.
   - CI confirms; it should not discover.
2. **Look things up before writing.** For any API, flag, chart key or image tag you aren't sure about, check the docs, `helm show values` or the registry first.
3. **Time-box infrastructure experiments to two attempts**, then take the documented configuration and record the deviation in an ADR.
4. **Wait on conditions, not timers:** `kubectl wait`, `rollout status`, or `until …; do sleep; done` inside one remote call.
5. **Batch fixes into one push.** Never push one-line fixes one by one.
6. **Record every multi-attempt problem here** (symptom → cause → fix → prevention).
7. **Never sit idle.** While a run or sync is in progress, work on the next item.
8. **Know the expected duration** of every step and report an overrun before being asked; give watchers deadlines.
9. **Commit before switching branches;** an uncommitted edit once travelled into an unrelated branch.
10. **Owner answers go into [owner-decisions.md](owner-decisions.md)** the same day, never only into a chat.
11. **Progress survives stoppages:** push each change to its `wip-*` branch immediately and update the PROGRESS LOG in `docs/status.md` on `main` after every step; run logs stream live and remain even if a runner dies.
12. **Stash untracked files before switching branches** (`git stash -u`). A `git add -A` on another branch sweeps them into an unrelated commit; a stray `Bounded.java` landed on main that way and triggered a full image rebuild (2026-11-20).
13. **cluster-verify fails fast:** a profile stops waiting when a pod crash-loops (≥ 5 restarts, still failing 3 min later) or sits in an image/config error for 4 min, and prints `FAILFAST <profile> after Ns: <pods>` plus the diagnostics. Read `CONVERGED / FAILFAST / TIMEOUT / SYNC FAILURE` per profile.
14. **Argo CD follows `main` live.** A merge to `main` while a cluster-verify run on `main` is in flight changes what that run installs. Hold merges that touch manifests or images until A/B or verification runs on `main` finish (2026-11-20).
15. **Commits carry no assistant trailers** (owner rule, it outranks any tool default): author `pratikmohanty1294 <pratikmohanty1294@users.noreply.github.com>`, plain message.
