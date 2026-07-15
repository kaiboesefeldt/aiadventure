# llama.cpp Prefill Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Evaluate the complete current player action into a llama.cpp KV cache slot while typing, then reuse the same slot for the streamed turn.

**Architecture:** Ollama is removed. A native llama.cpp gateway renders the existing ordered messages through `/apply-template`, then calls `/completion` with a leased `id_slot`; prefill uses `n_predict=0` and the actual turn streams tokens. A coroutine-safe coordinator serializes one active and one replaceable pending prefill per live browser game session.

**Tech Stack:** Kotlin/JVM 21, Ktor 3.4.1, Kotlin coroutines and Flow, JDK HttpClient, kotlinx.serialization, Kotlin/JS React, browser fetch, IndexedDB, llama.cpp native HTTP endpoints.

## Global Constraints

- Preserve prompt order: instruction, original scenario, history, complete action.
- Remove Ollama code, model selection, persistence fields, configuration, tests, dependency, and documentation.
- Server coordination state is ephemeral and bounded; browser IndexedDB remains the game-session authority.
- Never log scenario, history, action, rendered prompt, or LLM text; log request ID, hashed coordinator key, operation, state, and safe failure category only.
- Native prefill sends `cache_prompt=true`, the session's `id_slot`, `n_predict=0`, and never generates text.
- A turn uses the same rendered prompt and slot with `cache_prompt=true`, and is the only path that streams output.
- `POST /api/prefill` returns `202 Accepted` promptly. `POST /api/turn` stays SSE.
- Browser prefill has a 150 ms debounce, complete text, monotonic revision, and is disabled once a turn starts.

---

## File structure

| Path | Responsibility |
| --- | --- |
| `server/.../model/ApiModels.kt` | llama-only turn/prefill DTOs and cache metrics. |
| `server/.../llamacpp/LlamaCppGateway.kt` | template rendering, native completion, slots, metrics. |
| `server/.../prefill/PrefillCoordinator.kt` | mutex-protected per-session state machine. |
| `server/.../prefill/PrefillRegistry.kt` | TTL-bounded coordinator and slot ownership. |
| `server/.../Application.kt` | llama-only discovery, prefill, and coordinated turn routes. |
| `web/.../model/Models.kt` | llama-only stored session. |
| `web/.../api/AdventureApi.kt` | browser prefill API client. |
| `web/.../PrefillSubmission.kt` | debounce/revision/turn-suppression helper. |
| `web/.../Main.kt` | llama-only detail and input wiring. |
| `README.md`, `AGENTS.md`, `docs/UI-UX-Guide.md` | operation and verification contract. |

## Task 1: Add a backward-compatible prefill boundary

**Files:**

- Modify: `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/model/ApiModelsTest.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/api/TurnRequestMapperTest.kt`

**Interfaces:**

```kotlin
@Serializable data class TurnRequest(
    val modelId: String, val scenarioMarkdown: String, val history: List<ChatEntry>, val playerAction: String,
    val provider: ModelProvider = ModelProvider.OLLAMA, val llamaCppPort: Int? = null, val sessionId: String = "",
)
@Serializable data class PrefillRequest(
    val sessionId: String, val revision: Long, val modelId: String, val llamaCppPort: Int?,
    val scenarioMarkdown: String, val history: List<ChatEntry>, val playerAction: String,
)
@Serializable enum class PrefillDisposition { STARTED, QUEUED, STALE, TURN_IN_PROGRESS }
@Serializable data class PrefillAccepted(val disposition: PrefillDisposition)
```

- [ ] **Step 1: Write failing DTO and mapper tests**

```kotlin
@Test fun `mapper copies an optional live session identity`() {
    val request = TurnRequestMapper.map(session(), scenario(), "look", sessionId = "live-1")
    assertEquals("live-1", request.sessionId)
    assertEquals(8081, request.llamaCppPort)
}

@Test fun `prefill request carries an increasing revision`() {
    assertEquals(7L, PrefillRequest("live-1", 7, "model", 8081, "# Scenario", emptyList(), "look").revision)
}
```

- [ ] **Step 2: Run focused tests**

Run: `./gradlew :server:test --tests de.aidadventure.server.model.ApiModelsTest :web:jsTest --tests de.aidadventure.web.api.TurnRequestMapperTest`

Expected: FAIL because `sessionId`, `PrefillRequest`, and the mapper's optional live ID do not exist.

- [ ] **Step 3: Implement additive DTOs and mapper support**

```kotlin
object TurnRequestMapper {
    fun map(session: GameSession, scenario: Scenario, action: String, sessionId: String = "") = TurnRequest(
        modelId = session.modelId, scenarioMarkdown = scenario.markdown,
        history = session.messages.map { ChatEntry(it.role, it.content) }, playerAction = action,
        provider = session.provider, llamaCppPort = session.llamaCppPort, sessionId = sessionId,
    )
}
```

Keep `ModelProvider`, stored-session fields, and existing call sites intact in this task. The server/UI cutover tasks remove them together after all callers have migrated. Add matching client DTOs and JSON serialization; do not change IndexedDB schema yet.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :server:test --tests de.aidadventure.server.model.ApiModelsTest :web:jsTest --tests de.aidadventure.web.api.TurnRequestMapperTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt server/src/test/kotlin/de/aidadventure/server/model/ApiModelsTest.kt web/src/jsTest/kotlin/de/aidadventure/web/api/TurnRequestMapperTest.kt && git commit -m "feat: add prefill request boundary"`

## Task 2: Replace chat-completions with the native cache-aware llama.cpp gateway

**Files:**

- Modify: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Create: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppSlot.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt`
- Create: `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppSlotTest.kt`

**Interfaces:**

```kotlin
data class LlamaCppSlot(val id: Int)
data class CacheMetrics(val cachedTokens: Int?, val evaluatedTokens: Int?)
interface LlamaCppGateway {
    fun availableModels(port: Int): List<String>
    fun acquireIdleSlot(port: Int): LlamaCppSlot
    fun releaseSlot(port: Int, slot: LlamaCppSlot)
    suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics
    fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent>
}
```

- [ ] **Step 1: Write failing native HTTP tests**

Use a local `HttpServer` that records paths and decoded JSON.

```kotlin
@Test fun `prefill evaluates the templated prompt without generation`() = runBlocking {
    val metrics = gateway.prefill(request(), LlamaCppSlot(2))
    assertEquals(listOf("/apply-template", "/completion"), server.paths)
    val body = server.completionBodies.single().jsonObject
    assertEquals(2, body["id_slot"]!!.jsonPrimitive.int)
    assertTrue(body["cache_prompt"]!!.jsonPrimitive.boolean)
    assertEquals(0, body["n_predict"]!!.jsonPrimitive.int)
    assertEquals(CacheMetrics(17, 3), metrics)
}

@Test fun `turn uses the same slot and maps cache metrics`() = runBlocking {
    assertEquals(listOf(TurnStreamEvent.Token("Hello"), TurnStreamEvent.Done(TurnMetrics(null, 20, 17))),
        gateway.stream(request(), LlamaCppSlot(2)).toList())
}
```

- [ ] **Step 2: Run gateway tests**

Run: `./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest --tests de.aidadventure.server.llamacpp.LlamaCppSlotTest`

Expected: FAIL because `/apply-template`, `/completion`, slots, and cache metrics are absent.

- [ ] **Step 3: Implement template, slot, prefill, and completion handling**

```kotlin
@Serializable private data class CompletionRequest(
    val prompt: String,
    @SerialName("id_slot") val slotId: Int,
    @SerialName("cache_prompt") val cachePrompt: Boolean = true,
    @SerialName("n_predict") val predictedTokens: Int,
    val stream: Boolean,
)

override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics {
    val prompt = applyTemplate(request)
    val response = postCompletion(request.llamaCppPort, CompletionRequest(prompt, slot.id, true, 0, false))
    return CacheMetrics(response.tokensCached, response.tokensEvaluated)
}
```

`applyTemplate` maps `PromptFactory.build(request)` to roles and rejects a blank returned prompt. `acquireIdleSlot` reads `/slots`, accepts only a non-processing, process-unleased slot, and erases it before lease. `releaseSlot` erases it once. Native SSE `content` becomes `Token`; terminal `tokens_cached` and `tokens_evaluated` become `TurnMetrics`.

- [ ] **Step 4: Run gateway tests**

Run: `./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest --tests de.aidadventure.server.llamacpp.LlamaCppSlotTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add server/src/main/kotlin/de/aidadventure/server/llamacpp server/src/test/kotlin/de/aidadventure/server/llamacpp && git commit -m "feat(server): add native llama.cpp cache gateway"`

## Task 3: Implement the per-session coordinator and bounded registry

**Files:**

- Create: `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt`
- Create: `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillRegistry.kt`
- Create: `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`
- Create: `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillRegistryTest.kt`

**Interfaces:**

```kotlin
data class CoordinatorKey(val sessionId: String, val modelId: String, val port: Int)
interface PrefillCoordinator {
    suspend fun submit(request: PrefillRequest): PrefillDisposition
    suspend fun beginTurn(request: TurnRequest): Flow<TurnStreamEvent>
    suspend fun close()
}
interface PrefillRegistry {
    fun coordinatorFor(key: CoordinatorKey): PrefillCoordinator
    suspend fun remove(key: CoordinatorKey)
    suspend fun removeIdle(nowMillis: Long)
}
```

- [ ] **Step 1: Write failing transition tests with a controllable gateway fake**

```kotlin
@Test fun `newer pending prefill replaces older pending work`() = runTest {
    coordinator.submit(prefill(1, "open"))
    coordinator.submit(prefill(2, "open the"))
    coordinator.submit(prefill(3, "open the door"))
    fake.completePrefill(1)
    assertEquals(listOf("open", "open the door"), fake.prefilledActions)
}

@Test fun `turn drops pending prefill and follows active prefill on its slot`() = runTest {
    coordinator.submit(prefill(1, "open"))
    coordinator.submit(prefill(2, "open door"))
    val turn = async { coordinator.beginTurn(turn("open door")).toList() }
    fake.completePrefill(1)
    turn.await()
    assertEquals(listOf("open"), fake.prefilledActions)
    assertEquals(listOf("open door"), fake.turnedActions)
    assertEquals(fake.prefillSlots.single(), fake.turnSlots.single())
}

@Test fun `stale revision is ignored`() = runTest {
    coordinator.submit(prefill(3, "new"))
    assertEquals(PrefillDisposition.STALE, coordinator.submit(prefill(2, "old")))
}
```

- [ ] **Step 2: Run coordinator tests**

Run: `./gradlew :server:test --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --tests de.aidadventure.server.prefill.PrefillRegistryTest`

Expected: FAIL because the coordinator and registry do not exist.

- [ ] **Step 3: Implement mutex state and generation barriers**

```kotlin
private sealed interface State {
    data object Idle : State
    data class Running(val active: Work, val pending: PrefillRequest?) : State
    data class TurnWaiting(val active: Work, val turn: TurnRequest) : State
    data class Turning(val turn: TurnRequest) : State
}

override suspend fun submit(request: PrefillRequest): PrefillDisposition = mutex.withLock {
    if (state is State.Turning || state is State.TurnWaiting) return PrefillDisposition.TURN_IN_PROGRESS
    if (request.revision <= latestRevision) return PrefillDisposition.STALE
    latestRevision = request.revision
    when (val current = state) {
        State.Idle -> { startPrefillLocked(request); PrefillDisposition.STARTED }
        is State.Running -> { state = current.copy(pending = request); PrefillDisposition.QUEUED }
        else -> PrefillDisposition.TURN_IN_PROGRESS
    }
}
```

Use a `SupervisorJob`; only matching internal generation completions may start pending work or the waiting turn. `close`, TTL cleanup, and gateway failure cancel children, invalidate generation, and release the slot exactly once. Registry logs only a SHA-256-derived opaque key and removes idle coordinators after a finite TTL.

- [ ] **Step 4: Run coordinator tests**

Run: `./gradlew :server:test --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --tests de.aidadventure.server.prefill.PrefillRegistryTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add server/src/main/kotlin/de/aidadventure/server/prefill server/src/test/kotlin/de/aidadventure/server/prefill && git commit -m "feat(server): coordinate session prefill cache work"`

## Task 4: Add the Ktor routes and remove Ollama

**Files:**

- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`
- Modify: `server/build.gradle.kts`
- Delete: `server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt`
- Delete: `server/src/test/kotlin/de/aidadventure/server/ollama/KeepAliveDurationTest.kt`
- Delete: `server/src/test/kotlin/de/aidadventure/server/ollama/OllamaRequestParametersTest.kt`
- Delete: `server/src/test/kotlin/de/aidadventure/server/ollama/OllamaRequestTimeoutTest.kt`
- Delete: `server/src/test/kotlin/de/aidadventure/server/ollama/OllamaStreamingBridgeTest.kt`

**Interfaces:** Consumes Tasks 1--3 and produces `GET /api/models?port=8081`, `POST /api/prefill`, and coordinated SSE `POST /api/turn`.

- [ ] **Step 1: Write failing API tests**

```kotlin
@Test fun `prefill accepts without waiting for evaluation`() = testApplication {
    application { adventureModule(llamaCppGateway = fakeLlama, prefillRegistry = registry) }
    val response = client.post("/api/prefill") { contentType(ContentType.Application.Json); setBody(prefillRequest()) }
    assertEquals(HttpStatusCode.Accepted, response.status)
    assertEquals("{\"disposition\":\"STARTED\"}", response.bodyAsText())
}

@Test fun `invalid session identity and revision avoid gateway calls`() = testApplication {
    // blank sessionId and negative revision each return 400; fake call count is zero
}
```

- [ ] **Step 2: Run ApplicationTest**

Run: `./gradlew :server:test --tests de.aidadventure.server.ApplicationTest`

Expected: FAIL because prefill and llama-only route wiring do not exist.

- [ ] **Step 3: Wire routes and validation**

Make `adventureModule` accept `LlamaCppGateway`, `PrefillRegistry`, and `clock`. Remove `ModelCatalog`, `OllamaGateway`, and provider branches. Validate `sessionId` is nonblank and at most 256 characters and `revision >= 0` before registry lookup. `POST /api/prefill` invokes `submit` then responds `PrefillAccepted`; `/api/turn` calls `beginTurn` in the existing SSE writer. Keep request-ID-only safe error logging. The model endpoint validates `port` and calls `llamaCppGateway.availableModels(port)`.

- [ ] **Step 4: Remove dependency and run server tests**

Remove `implementation("dev.langchain4j:langchain4j-ollama:1.17.2")`. Update all server fakes to implement llama.cpp only.

Run: `./gradlew :server:test`

Expected: PASS; `rg -n -i "ollama|ModelProvider" server` returns no source/test matches.

- [ ] **Step 5: Commit**

Run: `git add server && git commit -m "feat(server): add coordinated llama.cpp prefill API"`

## Task 5: Implement browser debounce, silent prefill, and llama-only UI

**Files:**

- Create: `web/src/jsMain/kotlin/de/aidadventure/web/PrefillSubmission.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt`
- Create: `web/src/jsTest/kotlin/de/aidadventure/web/PrefillSubmissionTest.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/ActionSubmissionTest.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt`

**Interfaces:**

```kotlin
interface AdventureApi {
    suspend fun models(port: Int): List<String>
    suspend fun prefill(request: PrefillRequest): PrefillAccepted
    suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics
}
```

- [ ] **Step 1: Write failing debounce and turn-suppression tests**

```kotlin
@Test fun `debounce sends only newest complete input`() = runTest {
    prefill.changed(session(), scenario(), "live-1", "open")
    prefill.changed(session(), scenario(), "live-1", "open door")
    scheduler.runAll()
    assertEquals(listOf("open door"), api.prefilledActions)
    assertEquals(listOf(2L), api.prefillRevisions)
}

@Test fun `turn start cancels queued prefill`() = runTest {
    prefill.changed(session(), scenario(), "live-1", "open")
    prefill.turnStarted()
    scheduler.runAll()
    assertTrue(api.prefilledActions.isEmpty())
}
```

- [ ] **Step 2: Run focused browser tests**

Run: `./gradlew :web:jsTest --tests de.aidadventure.web.PrefillSubmissionTest --tests de.aidadventure.web.ActionSubmissionTest`

Expected: FAIL because `PrefillSubmission` and `AdventureApi.prefill` do not exist.

- [ ] **Step 3: Implement the helper and `POST /api/prefill`**

```kotlin
fun changed(session: GameSession, scenario: Scenario, sessionId: String, action: String) {
    if (turnActive || action.isBlank()) return
    pendingTimer?.let(cancel)
    val revision = ++nextRevision
    pendingTimer = schedule(150) {
        pendingTimer = null
        if (!turnActive && revision == nextRevision) scope.launch {
            submit(PrefillRequestMapper.map(session, scenario, sessionId, revision, action))
        }
    }
}

fun turnStarted() { turnActive = true; pendingTimer?.let(cancel); pendingTimer = null }
```

Use browser `fetch`, require 202, and decode `PrefillAccepted`. Swallow prefill failures inside this helper: do not render them, persist them, or disable Send. `PlayView` creates an opaque random live ID with `useRef`, calls `changed` after textarea edits, and calls `turnStarted` immediately before `TurnSubmission.submit`.

- [ ] **Step 4: Simplify the detail view**

Remove Ollama buttons, provider branches, and two-stage `LlamaCppConnectionState`. Keep one llama.cpp port input, discover models through `GET /api/models?port=...`, persist the chosen model and port, and verify the stored connection before resume. Keep Enter-to-send unchanged.

- [ ] **Step 5: Run all web tests**

Run: `./gradlew :web:jsTest`

Expected: PASS; `rg -n -i "ollama|ModelProvider|LlamaCppConnectionState" web` returns no source/test matches.

- [ ] **Step 6: Commit**

Run: `git add web && git commit -m "feat(web): prefill llama prompts while typing"`

## Task 6: Update documentation and prove cache reuse

**Files:**

- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/UI-UX-Guide.md`
- Create: `docs/llama-cpp-prefill-verification.md`

- [ ] **Step 1: Update the operating contract**

Document this launch shape:

```bash
llama-server --host 127.0.0.1 --port 8081 --model /absolute/path/to/model.gguf --parallel 4 --slots
```

State that `--parallel 1` is unnecessary: active sessions reserve individual slots; a full server returns a safe busy error rather than using another session's cache. Replace Ollama routes/commands with `/api/models?port=8081`; document `202` prefill and same-`sessionId` turn requests. Explain that only backend `tokens_cached`/`tokens_evaluated` fields prove reuse.

- [ ] **Step 2: Add an aggregate-only live verification checklist**

The new document records: start llama.cpp with at least two slots; type and pause for prefill; send the same action; record only request IDs and cache/evaluated-token counters; compare with a new session sent without pause; verify no prefill transcript entry. It explicitly forbids capturing scenario, action, prompt, history, or model output in logs/evidence.

- [ ] **Step 3: Scan docs and source for removed provider references**

Run: `rg -n -i "ollama|ModelProvider|OLLAMA_" README.md AGENTS.md docs server web; git diff --check`

Expected: no matches except historical design/plan documents; no whitespace errors.

- [ ] **Step 4: Run automated and live verification**

Run: `./gradlew check`

Expected: exit code 0.

Then execute the checklist. Stop all agent-started processes, run `./gradlew --stop`, and confirm no agent-started listeners remain on 8080, 8081, 8088, or 9222.

- [ ] **Step 5: Commit**

Run: `git add README.md AGENTS.md docs/UI-UX-Guide.md docs/llama-cpp-prefill-verification.md && git commit -m "docs: explain llama.cpp prefill cache workflow"`

## Plan self-review

- Spec coverage: Task 1 creates the backward-compatible prefill boundary; Task 2 implements the documented native cache mechanism; Task 3 implements the session coordinator, revision barriers, and TTL; Task 4 and Task 5 perform the atomic server/UI Ollama removal; Task 6 documents launch requirements and performs metric-based proof.
- Placeholder scan: all tasks name files, interfaces, tests, commands, expected outcomes, and commit boundaries.
- Type consistency: Task 1 defines the DTOs used by Tasks 2--5; Task 2 exposes the slot gateway consumed by Task 3; Task 3 exposes the registry injected in Task 4; Task 5 matches the task-1 web request shapes.
