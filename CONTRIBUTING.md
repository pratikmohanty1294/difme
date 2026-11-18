# Contributing to DIFME

Read [docs/CONVENTIONS.md](docs/CONVENTIONS.md) first: licence, footprint, privacy and dev-loop rules are hard requirements.
Then [docs/architecture/decisions.md](docs/architecture/decisions.md) (ADRs), [docs/roadmap.md](docs/roadmap.md) (what's done and next),
[verticals/README.md](verticals/README.md) (the product model) and [docs/knowledge-base/](docs/knowledge-base/) (lessons learnt the hard way).

## Where things run
Nothing needs a local machine.
- **GitHub Actions** builds, tests and publishes images on every push to `main`.
- **The Codespace** (`standardLinux32gb`: 4 cores, 16 GB RAM, 32 GB disk plus a 118 GB `/tmp`) runs the k3d cluster. Argo CD deploys `main` to it.
- **Any shell with `gh`** (laptop, CI, cloud agent) drives the Codespace through `ci/cs.sh`. It needs a token with the `repo` and `codespace` scopes in `GH_TOKEN`.

## Everyday commands
| Goal | Command |
|---|---|
| Check before pushing | `ci/cs.sh 'git fetch -q && git checkout -q wip-x && make check AREA=core'` (areas: core, flink, backoffice, deploy, verticals, api, tla) |
| Cluster status | `ci/cs.sh 'make status'` · `ci/cs.sh 'kubectl get pods -A \| grep -v Running'` |
| Bring a profile up | `ci/cs.sh bg up 'make up PROFILE=core'` then `ci/cs.sh log up` |
| Baseline / load test | `ci/cs.sh bg measure 'bash ci/measure.sh 200 60s'` then `ci/cs.sh log measure 80` |
| Recreate the cluster | `ci/cs.sh bg cluster 'make destroy; make cluster && make up PROFILE=core'` |

Long jobs always go through `bg`: they keep running if the caller disconnects. A keep-alive bound to the job stops the
60-minute idle timeout from interrupting it. One-time setup inside the Codespace: `gh auth login -s codespace`.

## Change flow
1. Branch `wip-<topic>`, commit, push the branch.
2. Verify in the Codespace: `make check AREA=…`. Dependency changes are compiled there. A quiet Maven log is not proof that tests ran: read `target/surefire-reports`.
3. Fast-forward `main`, push, delete the branch. CI builds images and Argo CD syncs.
4. Generated files (Argo CD apps, service manifests) are rendered in the Codespace and committed with the change. `make check AREA=deploy` fails if they're stale.
