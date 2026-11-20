# Contributing to DIFME

Start with [docs/status.md](docs/status.md) (where things stand, the next steps). Read [docs/CONVENTIONS.md](docs/CONVENTIONS.md) first: licence, footprint, privacy and dev-loop rules are hard requirements.
Then [docs/architecture/decisions.md](docs/architecture/decisions.md) (ADRs), [docs/roadmap.md](docs/roadmap.md) (what's done and next),
[verticals/README.md](verticals/README.md) (the product model) and [docs/knowledge-base/](docs/knowledge-base/) (lessons learnt the hard way).

## Where things run
Nothing needs a local machine.
- **GitHub Actions** builds, tests and publishes images on every push to `main`.
- **The Codespace** (`standardLinux32gb`: 4 cores, 16 GB RAM, 32 GB disk plus a 118 GB `/tmp`) runs the k3d cluster. Argo CD deploys `main` to it.
- **Any shell with `gh`** (laptop, CI, cloud agent) drives the Codespace through `ci/cs.sh`. It needs a token with the `repo` and `codespace` scopes in `GH_TOKEN`.
- **Without the Codespace** (billing, capacity, or a cloud agent session whose GitHub access has no Codespaces scope), everything runs in GitHub Actions, which is free with no minute cap for this public repo:
  - pushing a `wip-*` branch runs the same gates as `make check` (verify, core-services, flink-jobs, backoffice, dbt);
  - the manual `cluster-verify` workflow builds a throwaway cluster on a 4 vCPU / 16 GB runner (same shape as the Codespace) and installs base plus the profiles you list, with Argo CD tracking the dispatched branch. Its optional `measure` input runs `ci/measure.sh` afterwards;
  - `images` is dispatched by hand for a branch (it pushes `sha-*` tags only; release tags move on `main`).

## Everyday commands
| Goal | Command |
|---|---|
| Check before pushing | `ci/cs.sh 'git fetch -q && git checkout -q wip-x && make check AREA=core'` (areas: core, flink, backoffice, deploy, verticals, api, tla) |
| Cluster status | `ci/cs.sh 'make status'` · `ci/cs.sh 'kubectl get pods -A \| grep -v Running'` |
| Bring a profile up | `ci/cs.sh bg up 'make up PROFILE=core'` then `ci/cs.sh log up` |
| Baseline / load test | `ci/cs.sh bg measure 'bash ci/measure.sh 200 60s'` then `ci/cs.sh log measure 80` |
| Recreate the cluster | `ci/cs.sh bg cluster 'make destroy; make cluster && make up PROFILE=core'` |
| Same, in GitHub Actions | push `wip-x` (gates run on push) · `gh workflow run cluster-verify.yml --ref wip-x -f profiles=core,observability` · add `-f measure='200 60s'` for the baseline |

Long jobs always go through `bg`: they keep running if the caller disconnects. A keep-alive bound to the job stops the
60-minute idle timeout from interrupting it. One-time setup in the Codespace: put a token with the `codespace` scope in `~/.config/difme/gh-token` (mode 600).

## Change flow
1. Branch `wip-<topic>`, commit, push the branch.
2. Verify in the Codespace: `make check AREA=…`. While a job uses the main checkout (a rebuild, `make up`), check in a separate worktree (`git worktree add /tmp/wt wip-x`) so switching branches can't change files under the running job. Dependency changes are compiled there. A quiet Maven log is not proof that tests ran: read `target/surefire-reports`.
3. Fast-forward `main`, push, delete the branch. CI builds images and Argo CD syncs.
4. Generated files (Argo CD apps, service manifests) are rendered in the Codespace and committed with the change. `make check AREA=deploy` fails if they're stale.
