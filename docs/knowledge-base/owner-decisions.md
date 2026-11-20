# Owner rules, decisions and open questions

Imported from the agent sessions of 2026-11-17 to 2026-11-20 so that no answer lives only in a chat transcript.
Newest first within each section. When the owner answers a question, move it from **Open** to **Decided** with the date.

## Working rules (in force)
- **Nothing runs on the owner's PC** (2026-11-20): no builds, installs, scans, watchers, `git` or `gh`. Work happens in a cloud agent session, GitHub Actions, or the runtime VM/Codespace.
- **Never sit idle.** While something runs in the background, work on the next items using what is known about the system.
- **Be proactive about delays.** Know how long a step should take, track how long it has taken, and report an overrun without being asked. Watchers get deadlines and fail fast.
- **Install profiles in order, one at a time:** observability → lakehouse → process → security → ml → backoffice → hub → platform-ops. After each: memory report, fix defects, then decide whether it stays up or runs on demand. (With cluster-verify, independent profile sets may run in parallel on separate runners; each run still installs its profiles in this order.)
- **Squeeze resources with world-class techniques:** memory, I/O and CPU first. Tuning the source of open-source tools is allowed. Apply shared-memory, multi-process and multi-threading best practice.
- **Commit before switching branches** (an uncommitted edit once leaked into another branch). Time each step of a long sequence.
- **No company or vendor references and no assistant attribution** in the repo, commits or PRs; no assistant-instruction files.
- **Security:** the owner's personal email stays private and never goes to outside services. Tokens never go into the repo, files, logs or chat. A token pasted in chat is treated as leaked: revoke and rotate it.
- Licence, footprint, alternatives, contract-first and dev-loop rules: see [CONVENTIONS.md](../CONVENTIONS.md).

## Decided
| Date | Question | Owner's answer |
|---|---|---|
| 2026-11-20 | Where does the agent live, given nothing may run on the PC? | A cloud agent session (it can be driven from a phone). Heavy work runs in GitHub Actions. |
| 2026-11-20 | How does work continue with zero local execution? | Cloud session; even `git` and `gh` move off the PC. |
| 2026-11-20 | formio 4.11 hard-requires the proprietary `@formio/feature-flags`. Which way? | Shim it out (MIT stand-in in `vendor/formio/server/shims/`). |
| 2026-11-19 | Codespace blocked (HTTP 402 billing). How to continue? | Free alternative: Oracle Cloud Always Free A1. Keep retrying Oracle (capacity) and keep the Codespace alive for when the monthly hours reset. |
| 2026-11-19 | Saga routing vs DIRECT | Measure first (A/B done, see status.md); the routing choice itself is still open below. |
| 2026-11-19 | Install order for the remaining profiles | The recommended order above, one at a time. |
| 2026-11-19 | Sizing approach | Dev right-sizing overlay (Kyverno, CPU requests ÷ 4); measure after right-sizing with everything up. |
| 2026-11-19 | Argo CD controller restart loop | Reinstall Argo CD 2.14, then rebuild the Codespace. |
| 2026-11-18 | Memory pressure | Trim real memory first; Tier 1 (zswap + swap + kubelet LimitedSwap): yes; proceed with the control-plane CPU fix. |
| 2026-11-18 | Keep-alive token for long jobs | Store it as the user-level Codespaces secret `GH_TOKEN`, scoped to this repo (owner to rotate to a narrower token). |
| 2026-11-18 | RisingWave metadata store; S3 for streaming | Shared Postgres; SeaweedFS moves into the streaming profile. |

## Open (owner to decide)
| Since | Question | Context |
|---|---|---|
| 2026-11-20 | Canary SLO thresholds | `slo-gate` wants p99 ≤ 5 ms; measured cmd-svc p99 is about 250–330 ms, so every real rollout would abort. |
| 2026-11-19 | Payment routing: saga vs DIRECT | A/B in status.md: DIRECT p50 43 ms vs saga 97 ms at 15 req/s; 37.0 vs 33.4 req/s accepted at 200 req/s. Currently saga. |
| 2026-11-19 | Evidence chain: who witnesses in production, and where the operator key lives | Dev keys are generated at start. OpenBao transit is the candidate for the operator key. |
| 2026-11-19 | Collateral V = A, and the default partner tier | FR-14 collateral at close. |
| 2026-11-19 | `min_minor` collateral floors per vertical | ₹500 placeholders. |
| 2026-11-19 | v4.1 money letters | A charged at the merchant, B = A + Y + F paid by the buyer; the PRD has them swapped. |
| 2026-11-19 | Istio → Cilium mesh; Argo CD → Flux | Footprint candidates (status.md, next step 2). |
| 2026-11-19 | Kafka: `apache/kafka-native` vs Strimzi brokers | kafka-native can't run under Strimzi; adopting it means a plain StatefulSet broker. |

## Carried-over pending items
- Resume `event-archive` once the lakehouse profile (Polaris) is up.
- A startup smoke test for config parsing (the `.properties` inline-comment crash only showed at runtime).
- The Makefile `build` and `test` targets still name `services/hub`, `services/attestation` and `edge/core`, which don't exist yet.
