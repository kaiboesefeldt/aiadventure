# npm build workflow design

## Goal

Eliminate Kotlin/JS builds hanging on Yarn's shared installer mutex and make
JavaScript dependency resolution reproducible across normal checkouts and Git
worktrees.

## Package manager

Set `kotlin.js.yarn=false` in the repository's `gradle.properties`. Kotlin's
Gradle plugin then uses npm rather than its default managed Yarn instance, so
builds no longer contend for Yarn's global network mutex.

Generate the resulting npm lock state through the Kotlin/JS Gradle task and
commit every project-owned lockfile/store artifact required to reproduce that
resolution. Do not version `node_modules`, Gradle caches, or generated build
directories.

## Workflow rule

Document in `AGENTS.md` that Gradle invocations which can run Kotlin/JS setup
must be serialized per checkout. An interrupted Gradle run must be checked for
an inherited npm/Gradle child before starting another one. Normal verification
remains `./gradlew check`; no test command is to skip dependency installation
as a standing workaround.

## Verification

First run the Kotlin/JS dependency-install task and confirm it no longer
starts or waits for Yarn. Then run `./gradlew check` once without task
exclusions. Existing server and browser tests must complete. If npm reports a
package-resolution error, preserve its output and fix only the declared lock
state or package-manager configuration.
