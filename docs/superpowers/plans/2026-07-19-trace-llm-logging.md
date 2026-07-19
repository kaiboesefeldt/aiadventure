# TRACE LLM Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Add opt-in TRACE-level, incremental semantic LLM transcript logging and a server CLI log-level filter that defaults to INFO.

**Architecture:** ServerRuntimeConfig parses --log-level and main configures SLF4J Simple before Ktor creates loggers. LlamaCppGateway passes outgoing LangChain4j messages to a trace sink. A concurrent per-session cursor calculates the unchanged prefix and remembers a generated assistant response so it is not emitted again when it appears in a later history. Both streaming paths accumulate tokens and publish one final completion record.

**Tech Stack:** Kotlin/JVM, Ktor 3.4.1, SLF4J Simple 2.0.17, kotlinx.coroutines Flow, kotlin.test, JUnit Platform.

## Global Constraints

- --log-level=<TRACE|DEBUG|INFO|WARN|ERROR> is case-insensitive; omission means INFO.
- Keep the deterministic prompt prefix: instruction, original scenario, history, new action.
- Logging must not alter llama.cpp HTTP bodies, request order, session IDs, slots, cache metrics, or browser/server APIs.
- TRACE content logging is opt-in; operational gateway/model failure logs remain request-ID-only.
- A stream logs no individual token and logs its non-empty final response exactly once after stop or [DONE].
- The per-session cursor is concurrent, process-local, and never persisted.

---

### Task 1: Parse and apply the server log level

**Files:**

- Modify: server/src/main/kotlin/de/aidadventure/server/Application.kt:63-93
- Modify: server/src/test/kotlin/de/aidadventure/server/ServerRuntimeConfigTest.kt:7-34

**Interfaces:**

- Produces: ServerRuntimeConfig.logLevel: String, normalized to lower-case SLF4J Simple level.
- Consumes: --llama-port=<1-65535> and --log-level=<TRACE|DEBUG|INFO|WARN|ERROR>, in either order.

- [ ] **Step 1: Write the failing configuration tests**

Add default, case-insensitive value, combined-option, and invalid-value tests.

    @Test
    fun `uses INFO as the default log level`() {
        assertEquals("info", ServerRuntimeConfig.from(emptyMap()).logLevel)
    }

    @Test
    fun `uses the requested log level case insensitively`() {
        assertEquals("trace", ServerRuntimeConfig.from(emptyMap(), arrayOf("--log-level=TRACE")).logLevel)
    }

- [ ] **Step 2: Run the focused test to verify it fails**

Run: ./gradlew :server:test --tests de.aidadventure.server.ServerRuntimeConfigTest

Expected: compilation fails because ServerRuntimeConfig.logLevel does not exist.

- [ ] **Step 3: Implement argument parsing and early SLF4J configuration**

Extend ServerRuntimeConfig and replace the one-argument parser with parsing over all arguments. Reject unknown and duplicate options. Continue port validation through LlamaCppPort.parse. In main, directly after configuration parsing and before embeddedServer, add:

    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", config.logLevel)

- [ ] **Step 4: Run the focused test to verify it passes**

Run: ./gradlew :server:test --tests de.aidadventure.server.ServerRuntimeConfigTest

Expected: all ServerRuntimeConfigTest cases pass.

- [ ] **Step 5: Commit**

    git add server/src/main/kotlin/de/aidadventure/server/Application.kt server/src/test/kotlin/de/aidadventure/server/ServerRuntimeConfigTest.kt
    git commit -m "feat: add server log level option"

### Task 2: Add a concurrency-safe semantic trace transcript

**Files:**

- Create: server/src/main/kotlin/de/aidadventure/server/llamacpp/LlmTraceTranscript.kt
- Create: server/src/test/kotlin/de/aidadventure/server/llamacpp/LlmTraceTranscriptTest.kt

**Interfaces:**

- Produces: internal class LlmTraceTranscript with nextPrompt(sessionId: String, messages: List<LlmTraceMessage>): List<LlmTraceMessage> and recordFinalAssistantMessage(sessionId: String, content: String).
- Produces: internal data class LlmTraceMessage(val role: String, val content: String).

- [ ] **Step 1: Write the failing transcript tests**

Test full initial logging, appended suffix, changed historic entry, shortened history, and final assistant output not appearing again in the next history.

    @Test
    fun `returns only appended history after the first prompt`() {
        val transcript = LlmTraceTranscript()
        val first = listOf(message("system", "rules"), message("user", "look"))

        assertEquals(first, transcript.nextPrompt("session-1", first))
        assertEquals(
            listOf(message("assistant", "trees")),
            transcript.nextPrompt("session-1", first + message("assistant", "trees")),
        )
    }

- [ ] **Step 2: Run the focused test to verify it fails**

Run: ./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlmTraceTranscriptTest

Expected: compilation fails because LlmTraceTranscript does not exist.

- [ ] **Step 3: Implement the cursor**

Store immutable snapshots in ConcurrentHashMap<String, List<LlmTraceMessage>>. nextPrompt uses compute, finds the longest common prefix, replaces the snapshot with the full current list, and returns current.drop(prefixLength). recordFinalAssistantMessage atomically appends LlmTraceMessage("assistant", content) only for non-blank content not already at the snapshot tail.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: ./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlmTraceTranscriptTest

Expected: all delta, rewrite, truncation, and final-answer cursor cases pass.

- [ ] **Step 5: Commit**

    git add server/src/main/kotlin/de/aidadventure/server/llamacpp/LlmTraceTranscript.kt server/src/test/kotlin/de/aidadventure/server/llamacpp/LlmTraceTranscriptTest.kt
    git commit -m "feat: track incremental LLM trace history"

### Task 3: Emit TRACE prompts and one final response per LLM call

**Files:**

- Modify: server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt:42-452
- Modify: server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt:30-249

**Interfaces:**

- Consumes: LlmTraceTranscript from Task 2.
- Produces: constructor-injectable internal LlmTraceLogger for deterministic tests; production adapts to LoggerFactory.getLogger(LlamaCppGateway::class.java).trace.
- Produces: one prompt-operation record with historyDelta (possibly empty) and at most one finalResponse record per successful completion.

- [ ] **Step 1: Write the failing gateway tests with a recording trace logger**

Pass a recording sink to LlamaCppGateway. Execute prefill then turn with the same session, then a later request whose history includes the turn result plus a new action. Assert: the first prompt holds the full semantic sequence; repeated prompt has empty delta; later prompt has only the new action. Configure the native fake server to produce two chunks and assert exactly one final response equals their concatenation.

    assertEquals("Hello world", trace.finalResponses.single().content)
    assertTrue(trace.entries.none { it.kind == "token" })
    assertEquals(
        listOf(LlmTraceMessage("user", "Continue")),
        trace.prompts.last().historyDelta,
    )

- [ ] **Step 2: Run the focused test to verify it fails**

Run: ./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest

Expected: compilation fails because the trace logger constructor parameter and trace records do not yet exist.

- [ ] **Step 3: Implement trace logging without changing HTTP requests**

Build semantic messages before each /apply-template request, use them for serialization and tracing, and key cursor state by the request sessionId. Emit operation metadata even when historyDelta is empty. TRACE output contains escaped role/content pairs; escape backslash, newline, carriage return, and tab. Check isTraceEnabled before calculating or formatting content.

For stream(request, slot), append every non-empty NativeCompletionChunk.content to StringBuilder. At stop, emit metrics and exactly one final response trace before TurnStreamEvent.Done. Apply the same accumulator at [DONE] to stream(request). scoreFragment traces its returned content once. Prefill and warmup have no response text and log only semantic prompt plus completion metrics. Preserve the existing HMAC fingerprint and cache metrics.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: ./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest

Expected: existing gateway/cache assertions and the new incremental prompt and final-stream assertions pass.

- [ ] **Step 5: Commit**

    git add server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt
    git commit -m "feat: trace incremental LLM prompts and responses"

### Task 4: Verify the integrated server contract

**Files:**

- Modify: AGENTS.md only if its run documentation needs an explicit --log-level example; otherwise no documentation change.

**Interfaces:**

- Verifies: server accepts TRACE and defaults to INFO; tracing preserves native-completion cache behavior.

- [ ] **Step 1: Run server unit tests**

Run: ./gradlew :server:test

Expected: exit code 0, including runtime configuration, transcript, gateway, and cache-contract tests.

- [ ] **Step 2: Run the repository verification gate**

Run: ./gradlew check

Expected: exit code 0 for both modules.

- [ ] **Step 3: Inspect final diff and status**

Run: git diff --check && git status --short && git log --oneline -4

Expected: no whitespace errors; only planned source, tests, specification, and plan changes are present or committed.

- [ ] **Step 4: Commit the plan document**

    git add docs/superpowers/plans/2026-07-19-trace-llm-logging.md
    git commit -m "docs: add trace LLM logging plan"

