# Disable Ollama Thinking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Disable Ollama reasoning for every server-side game-master request.

**Architecture:** Keep the configuration in `LangChain4jOllamaGateway`, where the
Ollama client and its default request parameters are built. Extract the
parameter construction to an internal helper so a focused unit test can verify
the outbound `think` setting without starting Ollama.

**Tech Stack:** Kotlin/JVM 21, LangChain4j Ollama 1.17.2, Kotlin test, Gradle.

## Global Constraints

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Treat every LLM response as untrusted text.
- The server remains stateless; do not change the browser API or IndexedDB data.
- Do not log scenario, history, actions, or LLM text.

---

### Task 1: Disable reasoning in default Ollama request parameters

**Files:**
- Create: `server/src/test/kotlin/de/aidadventure/server/ollama/OllamaRequestParametersTest.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt:42-49`

**Interfaces:**
- Consumes: `OllamaChatRequestParameters.builder()` from LangChain4j and the existing `keepAliveSeconds` configuration.
- Produces: `internal fun defaultRequestParameters(keepAliveSeconds: Int): OllamaChatRequestParameters`, used by `LangChain4jOllamaGateway.play`.

- [ ] **Step 1: Write the failing test**

Create `server/src/test/kotlin/de/aidadventure/server/ollama/OllamaRequestParametersTest.kt`:

```kotlin
package de.aidadventure.server.ollama

import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaRequestParametersTest {
    @Test
    fun `default parameters disable Ollama thinking`() {
        val parameters = defaultRequestParameters(600)

        assertEquals(false, parameters.think())
        assertEquals(600, parameters.keepAlive())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:test --tests de.aidadventure.server.ollama.OllamaRequestParametersTest`

Expected: compilation fails because `defaultRequestParameters` does not yet exist.

- [ ] **Step 3: Write the minimal implementation**

In `server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt`, add the helper after the gateway interface:

```kotlin
internal fun defaultRequestParameters(keepAliveSeconds: Int): OllamaChatRequestParameters =
    OllamaChatRequestParameters.builder()
        .keepAlive(keepAliveSeconds)
        .think(false)
        .build()
```

Then replace the inline `defaultRequestParameters` builder in `LangChain4jOllamaGateway.play` with:

```kotlin
.defaultRequestParameters(defaultRequestParameters(keepAliveSeconds))
```

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `./gradlew :server:test --tests de.aidadventure.server.ollama.OllamaRequestParametersTest`

Expected: Gradle exits with code 0 and the test passes.

- [ ] **Step 5: Run the full repository verification**

Run: `./gradlew check`

Expected: Gradle exits with code 0; server and web tests pass.

- [ ] **Step 6: Commit the implementation**

```bash
git add server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt \
  server/src/test/kotlin/de/aidadventure/server/ollama/OllamaRequestParametersTest.kt
git commit -m "fix: disable Ollama thinking"
```
