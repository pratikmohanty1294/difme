# Build, CI and toolchain

| Symptom | Cause | Fix |
|---|---|---|
| `package org.apache.flink.api.java.utils does not exist` | Flink 2.x moved `ParameterTool` | `org.apache.flink.util.ParameterTool` |
| Surefire: conflicting JUnit versions | `flink-test-utils` pulls an old vintage engine | import `junit-bom`, exclude `junit-vintage-engine` |
| Flink MiniCluster: `Could not deserialize stream node` under `mvn exec:java` | Maven's classloader | `exec:exec` (separate JVM) |
| `-Dnative` builds a JVM jar | no `native` profile in the POM | `-Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false` |
| Native image fails in Temporal's shaded Netty | Temporal SDK isn't native-image clean | process-gateway runs on JVM + AOT cache (ADR-012) |
| pnpm `ERR_PNPM_IGNORED_BUILDS` | pnpm ≥ 11 blocks install scripts | `allowBuilds` map in `pnpm-workspace.yaml` |
| MF DTS generation fails | remotes are runtime-registered | `dts: false` in the Module Federation plugin |
| Angular: `Failed to read index HTML` | web-component bundle has no page | `"index": false` |
| Trivy action tag not found | pinned tag doesn't exist | run the Trivy CLI from its pinned image |
| Nginx image blocked by Trivy (brotli CVE) | base image lags Alpine fixes | `apk upgrade` in the Dockerfile |
| Windows commits lose executable bits | Git on Windows | `git update-index --chmod=+x` |
| Shell heredocs mangle backslashes (Windows Bash tool) | quoting layers | edit files with editor tools or patch files, never inline JSON/regex in shell |

### `release version 25 not supported` in the Codespace (CI green)
- **Root cause:** the devcontainer installed only JDK 21 (for Flink), so `make check AREA=core` had never actually run in the Codespace.
- **Fix:** the devcontainer installs Temurin 25 with 21 as an additional version, and `ci/check.sh` selects the JDK per area (core → 25, flink → 21).

### JVM memory budget in containers (measured 2026-11-18, cmd-svc at 200 req/s)
- `MaxRAMPercentage=70` with a 320 MiB limit left about 96 MiB for metaspace, code cache, thread stacks and Netty direct buffers, so cmd-svc was OOM-killed under load (exit 137).
- Now 55% heap, `MaxDirectMemorySize=32m`, and cmd-svc at 448 MiB (it carries Hibernate, the Temporal SDK, gRPC and the Redis client). Re-measure before shrinking.
- Quarkus Redis client defaults (6 connections, 24 waiters) saturate at a few hundred cache calls per second: `ConnectionPoolTooBusyException`. Size `quarkus.redis.max-pool-size`/`max-pool-waiting` to the call rate.
