# DIFME — engineering conventions

- Architecture source of truth: Financial Platform Architecture v1.2 (11 layers). The coverage matrix in README.md maps every PDF component to its path. Keep it complete when adding or changing components.
- Reuse before adding (owner 2026-11-21): when copying in another system, map every infrastructure component it expects (database, cache, broker, object store, search, identity, secrets, workflow, observability) to the one DIFME already runs and use that; never deploy a second one. Record the mapping in its VENDOR.md and in docs/architecture/component-reuse.md.
- Licence rule (hard): OSI-approved **and** free with no limitations. No BSL, SSPL, ELv2 or source-available licences, no watermark/attribution-display terms, no usage-capped free tiers, no open-core where the needed feature is paid. Copyleft (GPL, AGPL, MPL) is fine. Check every new dependency, chart and image. Swaps are recorded in docs/architecture/decisions.md.
- Alternatives: when a spec names a component, look for a newer or better free alternative and let the owner choose before adopting it.
- Everything runs remotely (Codespaces + GitHub Actions). Do not assume a local machine.
- IDs: UUIDv7 everywhere (idempotency keys, event IDs, PKs, outbox IDs, workflow IDs).
- Command handlers must be pure. No `System.currentTimeMillis()`, `Instant.now()`, `UUID.randomUUID()` or I/O inside handlers. The Semgrep gate in policies/semgrep enforces this.
- Contract-first: change a spec in api/ before changing the code that implements it.
- Runtime: `make cluster`, then `make up PROFILE=<core|hub|streaming|data|lakehouse|ml|observability|security|backoffice|edge|all>`.
- Footprint (hard): lowest RAM/CPU/disk/network I/O for the highest throughput and lowest latency. Measure, don't assume: `bash ci/measure.sh RATE DURATION` gives the k6 baseline and per-pod CPU/RAM. A footprint change is kept only if it beats the recorded baseline.
- Commits and PRs: plain messages, no tool or vendor attribution trailers, and no company or vendor references anywhere in the repo. No assistant-instruction files (e.g. CLAUDE.md) in the repo.
- Privacy: never put personal data (names, emails) in code, commits, URLs or requests to outside services.
- Dev loop: verify before merging: `make check AREA=…` in the Codespace, or the GitHub Actions gates that run on every `wip-*` push (plus `cluster-verify` for runtime changes); then fast-forward main. Look up APIs and artifact versions before using them. Infra fixes are time-boxed to two attempts, then step back. No sleep-polling: long jobs go through `ci/bg.sh`.
- Knowledge: every non-obvious lesson goes into docs/knowledge-base/ (cluster, gitops, build) in the same change.
