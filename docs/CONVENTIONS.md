# DIFME — engineering conventions

- Architecture source of truth: Financial Platform Architecture v1.2 (11 layers). The coverage matrix in README.md maps every PDF component to its path. Keep it complete when adding or changing components.
- Licence rule: OSI-approved only. Swaps are recorded in docs/architecture/decisions.md. If something is paid-only, propose the best free alternative and ask the owner before adding it.
- Everything runs remotely (Codespaces + GitHub Actions). Do not assume a local machine.
- IDs: UUIDv7 everywhere (idempotency keys, event IDs, PKs, outbox IDs, workflow IDs).
- Command handlers must be pure. No `System.currentTimeMillis()`, `Instant.now()`, `UUID.randomUUID()` or I/O inside handlers. The Semgrep gate in policies/semgrep enforces this.
- Contract-first: change a spec in api/ before changing the code that implements it.
- Runtime: `make cluster`, then `make up PROFILE=<core|hub|streaming|data|lakehouse|ml|observability|security|backoffice|edge|all>`.
