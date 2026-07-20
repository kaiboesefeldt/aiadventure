# llama.cpp timeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Use one 300-second timeout for every production llama.cpp HTTP operation.

**Architecture:** `LlamaCppGateway` has one named `Duration` constant. Its default `HttpClient` connect timeout and all `HttpRequest.timeout` calls use the same constant, removing the separate 5-second and 60-second values while retaining gateway API behavior.

**Tech Stack:** Kotlin/JVM, Java `HttpClient`, kotlin.test, Gradle.

## Global Constraints

- The timeout is exactly 300 seconds.
- Only production llama.cpp HTTP access changes; test harness and Gradle timeouts remain unchanged.
- Prompt-cache request ordering, session IDs, slot reuse, and metrics remain unchanged.

---

### Task 1: Centralize llama.cpp HTTP timeout

**Files:**
- Modify: `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Modify: `docs/superpowers/specs/2026-07-20-llama-cpp-timeout-design.md`
- Modify: `docs/superpowers/plans/2026-07-20-llama-cpp-timeout.md`

**Interfaces:**
- Consumes: `LlamaCppGateway` default construction.
- Produces: A single `LLAMA_CPP_TIMEOUT` duration used by all production gateway HTTP calls.

- [x] **Step 1: Write the failing regression test**

Add a gateway test that accesses the existing default timeout field reflectively and asserts `Duration.ofSeconds(300)`:

```kotlin
val timeout = LlamaCppGateway::class.java
    .getDeclaredField("DEFAULT_TIMEOUT")
    .also { it.isAccessible = true }
    .get(null) as Duration

assertEquals(Duration.ofSeconds(300), timeout)
```

- [x] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest`

Expected: the timeout assertion fails because the current default is 5 seconds.

- [x] **Step 3: Implement the minimal central timeout**

Replace the two duration fields with one named constant and use it for the default client and each request:

```kotlin
private val LLAMA_CPP_TIMEOUT: Duration = Duration.ofSeconds(300)
```

Update every `HttpRequest.Builder.timeout(...)` in `LlamaCppGateway` and both default `HttpClient` constructors to use `LLAMA_CPP_TIMEOUT`. Do not change request bodies, paths, session IDs, or slot handling.

- [x] **Step 4: Run the focused test to verify it passes**

Run: `./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest`

Expected: `BUILD SUCCESSFUL` and all gateway tests pass.

- [x] **Step 5: Run full verification and commit**

Run: `./gradlew check`

Expected: `BUILD SUCCESSFUL`.

```bash
git add server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt \
  server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt \
  docs/superpowers/specs/2026-07-20-llama-cpp-timeout-design.md \
  docs/superpowers/plans/2026-07-20-llama-cpp-timeout.md
git commit -m "fix: centralize llama cpp timeouts"
```
