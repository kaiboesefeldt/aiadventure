# Flush Stream Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Commit each streamed game-master session update to the browser UI immediately.

**Architecture:** Add a narrow `flushStreamUpdate` wrapper around React DOM
`flushSync` in the web module. `PlayView` uses it only for the transient
streamed-session callback; starting and completed-session updates retain their
normal scheduling and all server/SSE behavior remains unchanged.

**Tech Stack:** Kotlin/JS, React 19, Kotlin React DOM wrappers, Kotlin test, Gradle.

## Global Constraints

- Browser IndexedDB owns scenarios and sessions; save the game-master message only after `done`.
- Treat generated text as untrusted and render it as text nodes only.
- Keep sending disabled until the stream completes or fails.
- Do not alter the server SSE endpoint, prompt construction, or Ollama settings.

---

### Task 1: Flush transient streamed-session updates

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt:1-290`
- Create: `web/src/jsTest/kotlin/de/aidadventure/web/StreamRenderTest.kt`

**Interfaces:**
- Consumes: `onStreamed: (GameSession) -> Unit` from `TurnSubmission.submit`.
- Produces: `internal fun flushStreamUpdate(update: () -> Unit)` used only by the PlayView streamed-session callback.

- [ ] **Step 1: Write the failing test**

Create `web/src/jsTest/kotlin/de/aidadventure/web/StreamRenderTest.kt`:

```kotlin
package de.aidadventure.web

import kotlin.test.Test
import kotlin.test.assertTrue

class StreamRenderTest {
    @Test
    fun `stream update runs before the callback returns`() {
        var updated = false

        flushStreamUpdate { updated = true }

        assertTrue(updated)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :web:browserTest --tests de.aidadventure.web.StreamRenderTest`

Expected: test compilation fails because `flushStreamUpdate` does not yet exist.

- [ ] **Step 3: Add the minimal synchronous render wrapper**

In `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`, import
`react.dom.flushSync` and add:

```kotlin
internal fun flushStreamUpdate(update: () -> Unit) {
    flushSync(update)
}
```

In the `TurnSubmission.submit` call inside `PlayView`, keep
`onStarted = props.onSessionChanged` unchanged and set the streamed callback to:

```kotlin
onStreamed = { session ->
    flushStreamUpdate { props.onSessionChanged(session) }
},
```

Do not wrap the final `props.onSessionChanged(completed.session)` call.

- [ ] **Step 4: Run focused and full verification**

Run: `./gradlew :web:browserTest --tests de.aidadventure.web.StreamRenderTest`

Expected: Gradle exits with code 0 and the synchronous-callback test passes.

Run: `./gradlew check`

Expected: Gradle exits with code 0; server and browser tests pass.

- [ ] **Step 5: Manual browser smoke test**

Run the API and browser client, submit an action that produces multiple tokens,
and confirm the game-master paragraph grows before the terminal `done` event.

- [ ] **Step 6: Commit**

```bash
git add web/src/jsMain/kotlin/de/aidadventure/web/Main.kt \
  web/src/jsTest/kotlin/de/aidadventure/web/StreamRenderTest.kt
git commit -m "fix(web): flush streamed token updates"
```
