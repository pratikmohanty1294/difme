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
