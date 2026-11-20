# Owner rules, decisions and open questions

Imported from the agent sessions of 2026-11-17 to 2026-11-20 so that no answer lives only in a chat transcript.
Newest first within each section. When the owner answers a question, move it from **Open** to **Decided** with the date.

## Working rules (in force)
- **Optimisation order** (2026-11-20): memory first until it is as low as it goes, then CPU, then disk I/O, then network I/O bandwidth. Each stage is measured per process/thread (`ci/proc-report.sh`) and a change stays only if it beats the baseline.
- **Save progress after every step** (2026-11-20, "so that nothing is lost in abrupt stoppage of GitHub Actions or network issues"): push work to a `wip-*` branch as soon as it exists, and update the PROGRESS LOG in `docs/status.md` on `main` (branch, fix, run, next action) after each step.
- **Optimise for memory, always; keep as much free as possible** (2026-11-20). Every profile and component is sized from measurements, and a change stays only if it beats the recorded baseline (CONVENTIONS.md footprint rule).
- **Nothing runs on the owner's PC** (2026-11-20): no builds, installs, scans, watchers, `git` or `gh`. Work happens in a cloud agent session, GitHub Actions, or the runtime VM/Codespace.
- **Never sit idle.** While something runs in the background, work on the next items using what is known about the system.
- **Be proactive about delays.** Know how long a step should take, track how long it has taken, and report an overrun without being asked. Watchers get deadlines and fail fast.
- **Install profiles in order, one at a time:** observability → lakehouse → process → security → ml → backoffice → hub → platform-ops. After each: memory report, fix defects, then decide whether it stays up or runs on demand. (With cluster-verify, independent profile sets may run in parallel on separate runners; each run still installs its profiles in this order.)
- **Squeeze resources with world-class techniques:** memory, I/O and CPU first. Tuning the source of open-source tools is allowed. Apply shared-memory, multi-process and multi-threading best practice.
- **Commit before switching branches** (an uncommitted edit once leaked into another branch). Time each step of a long sequence.
- **No company or vendor references and no assistant attribution** in the repo, commits or PRs; no assistant-instruction files.
- **Security:** the owner's personal email stays private and never goes to outside services. Tokens never go into the repo, files, logs or chat. A token pasted in chat is treated as leaked: revoke and rotate it.
- **Open source with no limitations** (2026-11-17, "make sure everything we use should be open source and free to use with no limitations"): OSI-approved **and** no watermark/attribution-display terms, no BSL/SSPL/ELv2/source-available, no usage-capped free tiers or paid feature gates; copyleft is fine. This overrode the earlier bpmn-js watermark exception. State unavoidable platform quotas honestly (e.g. Codespaces hours).
- **When something fails or a better free option exists, offer the alternatives as a choice** and let the owner pick before adopting.
- **Everything is driven from the cloud** (2026-11-18, from the phone: "Can't the watcher not be in the remote server, so that I can control everything from this cloud session"). Watchers and long waits run remotely (Actions, runtime VM), never on the PC. Lockfile generation and licence scans go into a small `workflow_dispatch` job.
- **Parallel when safe; lightweight with maximum throughput** (named owner rules, see Footprint in CONVENTIONS.md).
- **Prava skills in every session** (2026-11-17, "make sure they are available in any future sessions also"): `prava-pay` and `prava-shopping` are installed globally by the Default cloud environment's setup script and on the PC.
- Licence, footprint, alternatives, contract-first and dev-loop rules: see [CONVENTIONS.md](../CONVENTIONS.md).

## Decided
| Date | Question | Owner's answer |
|---|---|---|
| 2026-11-20 | Harbor fails its first migration on YugabyteDB (it supports PostgreSQL only). Where should its database live? | Shared platform Postgres (ADR-019). |
| 2026-11-20 | OpenBao is 3-replica HA (unschedulable on one node) and never initialized. How should it run in dev/verification? | 1 Raft replica plus a bootstrap Job (init, unseal with a dev key in a Secret, PKI mount and Kubernetes auth for SPIRE); HA stays for production overlays. |
| 2026-11-20 | attestation-verifier has no source and no image yet. What until the code exists? | Take it out of the security profile for now (Veraison stays); it returns with its code. |
| 2026-11-20 | Commit identity (the vendor rule) | Commits use `pratikmohanty1294@users.noreply.github.com`; only unmerged branch commits were re-authored, main history left as is. |
| 2026-11-20 | Remove YugabyteDB (~440 MiB in dev) and move everything to the shared Postgres? | Keep it; measure first what the move would save on the runners and bring the numbers before any change. |
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

## Product requirement context (owner's questions, 2026-11-17)
`docs/GOALS.md` still says "product goals: to be defined by the owner". The owner's research questions show the direction of DIFME/DiscountAI (ADR-020):
- Is there an aggregator (like quick-commerce APIs) that gives one integration for product-feed discovery across merchants?
- How to get the best price for a product, including affiliate coupons? Do affiliate coupons apply when merchant PWAs open inside affiliate sites?
- How does agent payment-credential handover work in recently launched agent wallets, which global standards apply, and does it happen in the browser or through APIs?
- Paying with the user's own card through an agent wallet (Prava skills installed for this).

## Carried-over pending items
- Measure the memory saving of running payments + tools on the shared Postgres instead of YugabyteDB (owner asked for numbers before any move).
- Resume `event-archive` once the lakehouse profile (Polaris) is up.
- A startup smoke test for config parsing (the `.properties` inline-comment crash only showed at runtime).
- The Makefile `build` and `test` targets still name `services/hub`, `services/attestation` and `edge/core`, which don't exist yet.
