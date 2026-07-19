# Namespace Log Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Suppress all non-`de.aidadventure` INFO, DEBUG, and TRACE records while preserving the requested low-level logging for AiAdventure code.

**Architecture:** Set SLF4J Simple's global default to `warn` before Ktor starts, then set `de.aidadventure` to the validated CLI level. Keep that property mutation in a small testable Kotlin unit.

**Tech Stack:** Kotlin/JVM 21, SLF4J Simple 2.0.17, kotlin.test, Gradle.

## Global Constraints

- Only logger names beginning with `de.aidadventure` may emit INFO, DEBUG, or TRACE records.
- WARN and ERROR remain visible from every logger namespace.
- The existing `--log-level` parsing and default `info` remain unchanged.
- No API, prompt, llama.cpp, slot, or cache behavior changes.

### Task 1: Configure the SLF4J Simple Namespace Filter

**Files:**
- Create: `server/src/main/kotlin/de/aidadventure/server/ServerLogging.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Create: `server/src/test/kotlin/de/aidadventure/server/ServerLoggingTest.kt`

- [x] **Step 1: Write the failing regression test**

The test invokes `ServerLogging.configure(ServerRuntimeConfig.from(...).logLevel)`
and asserts:

```kotlin
assertEquals("warn", System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"))
assertEquals("trace", System.getProperty("org.slf4j.simpleLogger.log.de.aidadventure"))
```

- [x] **Step 2: Verify the test fails before production code exists**

Run:

```bash
./gradlew --no-daemon :server:compileTestKotlin -x kotlinNpmInstall -x kotlinStorePackageLock -x :web:browserProductionWebpack --console=plain
```

Observed: `Unresolved reference 'ServerLogging'`.

- [x] **Step 3: Implement the minimal configuration**

```kotlin
internal object ServerLogging {
    fun configure(logLevel: String) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        System.setProperty("org.slf4j.simpleLogger.log.de.aidadventure", logLevel)
    }
}
```

Call `ServerLogging.configure(config.logLevel)` in `main` before
`embeddedServer(...)`.

- [x] **Step 4: Verify the focused regression test**

Run:

```bash
./gradlew --no-daemon :server:test --tests de.aidadventure.server.ServerLoggingTest -x kotlinNpmInstall -x kotlinStorePackageLock -x :web:browserProductionWebpack --console=plain
```

Observed: `BUILD SUCCESSFUL`.

- [x] **Step 5: Verify the server test suite**

Run:

```bash
./gradlew --no-daemon :server:test -x kotlinNpmInstall -x kotlinStorePackageLock -x :web:browserProductionWebpack --console=plain
```

Observed: `BUILD SUCCESSFUL`.

- [x] **Step 6: Run the repository verification gate**

```bash
NODE_PATH="$PWD/build/js/node_modules:$PWD/build/js/packages/aiadventure3-web-test/node_modules" ./gradlew --no-daemon check --console=plain
```

Observed: `BUILD SUCCESSFUL`.

- [x] **Step 7: Commit implementation, test, specification, and plan**

```bash
git add server/src/main/kotlin/de/aidadventure/server/ServerLogging.kt server/src/main/kotlin/de/aidadventure/server/Application.kt server/src/test/kotlin/de/aidadventure/server/ServerLoggingTest.kt docs/superpowers/specs/2026-07-19-namespace-log-filter-design.md docs/superpowers/plans/2026-07-19-namespace-log-filter.md
git commit -m "fix: filter low-level dependency logs"
```
