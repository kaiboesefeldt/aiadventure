# Server-configured llama.cpp connection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configure the one local llama.cpp server at application startup and remove port/model choices from the browser.

**Architecture:** Parse a validated `--llama-port` once in server startup and inject a configured gateway into routes. Route handlers derive the sole model from that gateway; the Kotlin/JS app creates and resumes browser-local sessions without connection metadata.

**Tech Stack:** Kotlin/JVM, Ktor, Kotlin/JS React, IndexedDB, kotlin.test.

## Global Constraints

- The llama.cpp host is localhost and its default port is 8081.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
- A session's prefill and turn retain the same sessionId and one warm llama.cpp slot.
- Operational gateway logs contain request IDs only, never game content or LLM text.

---

### Task 1: Server-owned llama.cpp configuration

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/ServerRuntimeConfigTest.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`

**Interfaces:**
- Consumes: `main(args: Array<String>)`, `LlamaCppPort.parse(value: String)`.
- Produces: `ServerRuntimeConfig.llamaCppPort: Int` and a gateway whose requests use that port.

- [ ] **Step 1: Write failing server tests**

```kotlin
assertEquals(8081, ServerRuntimeConfig.from(emptyMap(), emptyArray()).llamaCppPort)
assertEquals(8082, ServerRuntimeConfig.from(emptyMap(), arrayOf("--llama-port=8082")).llamaCppPort)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :server:test --tests de.aidadventure.server.ServerRuntimeConfigTest`

Expected: compilation failure because the configuration has no command-line llama.cpp port.

- [ ] **Step 3: Implement configuration and routes**

```kotlin
val config = ServerRuntimeConfig.from(System.getenv(), args)
val gateway = LlamaCppGateway(config.llamaCppPort)
embeddedServer(Netty, port = config.port, host = config.host) {
    adventureModule(llamaCppGateway = gateway)
}.start(wait = true)
```

Remove `/api/models`; make prefill, warmup, and turn obtain the configured model identifier from the gateway rather than accepting port/model fields from JSON.

- [ ] **Step 4: Run focused server tests**

Run: `./gradlew :server:test --tests de.aidadventure.server.ServerRuntimeConfigTest --tests de.aidadventure.server.ApplicationTest`

Expected: PASS.

### Task 2: Browser session-only navigation

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt`

**Interfaces:**
- Consumes: scenario detail view and browser-local `GameSession` records.
- Produces: direct `New session` creation and direct resume without model/port controls.

- [ ] **Step 1: Write the failing browser navigation test**

```kotlin
container.clickButtonNamed("New session")
nextBrowserTick()
assertNotNull(container.querySelector("#player-action"))
assertNull(container.buttonNamed("Find models"))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :web:browserTest --tests de.aidadventure.web.AdventureAppNavigationTest`

Expected: FAIL because the detail view still requires model discovery.

- [ ] **Step 3: Implement the session-only UI and request contracts**

```kotlin
ReactHTML.button {
    className = ClassName("primary-button")
    onClick = { props.onCreate() }
    +"New session"
}
```

Remove `AdventureApi.models`, port/model state, and their persisted browser-session fields. Keep session IDs and transcript persistence unchanged.

- [ ] **Step 4: Run focused browser tests**

Run: `./gradlew :web:browserTest --tests de.aidadventure.web.AdventureAppNavigationTest`

Expected: PASS.

### Task 3: End-to-end regression verification

**Files:**
- Modify: `README.md`
- Modify: `docs/llama-cpp-prefill-verification.md`

**Interfaces:**
- Consumes: server startup command and browser flow from Tasks 1 and 2.
- Produces: documented default/override startup and cache verification commands.

- [ ] **Step 1: Update the startup and cache verification documentation**

```markdown
./gradlew :server:run
./gradlew :server:run --args='--llama-port=8082'
```

- [ ] **Step 2: Run the full project verification**

Run: `./gradlew check`

Expected: PASS for server and web checks.

- [ ] **Step 3: Inspect the complete diff**

Run: `git diff --check && git status --short`

Expected: no whitespace errors and only the intended files changed.
