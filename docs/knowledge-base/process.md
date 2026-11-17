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
