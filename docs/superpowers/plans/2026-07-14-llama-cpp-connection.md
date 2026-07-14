# llama.cpp Connection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-session `llama.cpp` connectivity on localhost with a default port of `8081`, model discovery, OpenAI-compatible streaming turns, and a two-stage browser connection/start control.

**Architecture:** Keep the Ktor server stateless and route each turn through a provider-aware gateway. Preserve the existing Ollama path and prompt factory; add a raw JDK `HttpClient` implementation for `llama.cpp` at `http://localhost:<port>/v1`. The Kotlin/JS client persists provider/model/port in IndexedDB and performs discovery through the server before starting a `llama.cpp` session.

**Tech Stack:** Kotlin/JVM 21, Kotlin/JS, Ktor 3.4.1, kotlinx.serialization, Kotlin coroutines Flow, LangChain4j Ollama 1.17.2, React wrappers, IndexedDB, JDK `HttpClient`.

## Global Constraints

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Treat every LLM response as untrusted text.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
- `llama.cpp` is always addressed as `http://localhost:<port>`; the default port is `8081`.
- Do not log scenario, history, actions, or LLM text; operational failures log request ID and technical failure class only.
- Existing Ollama requests and IndexedDB records remain backward compatible.
- Do not claim prompt-cache metrics unless the active integration returns them.

## File Map

- Modify `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt` for provider and port fields.
- Modify `server/src/main/kotlin/de/aidadventure/server/Application.kt` for discovery, validation, gateway wiring, and provider dispatch.
- Modify `server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt` only where the common gateway boundary must change; keep Ollama behavior intact.
- Create `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt` for discovery and OpenAI-compatible streaming.
- Create focused server tests under `server/src/test/kotlin/de/aidadventure/server/llamacpp/` and extend `ApplicationTest.kt` only for route behavior.
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt` for persisted provider and port.
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt` for discovery and turn mapping.
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt` for connection state, resume behavior, and UI controls.
- Extend `web/src/jsTest/kotlin/de/aidadventure/web/api/` and add a focused UI-state test helper if needed.
- Modify `README.md` with the local `llama.cpp` startup and play flow.

### Task 1: Add the provider-aware wire model and port rules

**Files:**
- Test: `server/src/test/kotlin/de/aidadventure/server/model/ApiModelsTest.kt`
- Test: `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppPortTest.kt`
- Create: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppPort.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`

**Interfaces:**
- Produce `ModelProvider` with `OLLAMA` as the serialization default and `LLAMA_CPP` as the second provider.
- Produce `LlamaCppPort.DEFAULT = 8081` and `LlamaCppPort.parse(value: String): Int`.
- Extend `TurnRequest` with `provider: ModelProvider = ModelProvider.OLLAMA` and `llamaCppPort: Int? = null`.

- [ ] **Step 1: Write failing serialization and port tests.**

```kotlin
@Test
fun `old turn JSON defaults to Ollama`() {
    val request = Json.decodeFromString<TurnRequest>("""
        {"modelId":"small","scenarioMarkdown":"# S","history":[],"playerAction":"look"}
    """)
    assertEquals(ModelProvider.OLLAMA, request.provider)
    assertNull(request.llamaCppPort)
}

@Test
fun `llama cpp port defaults to 8081 and rejects out of range values`() {
    assertEquals(8081, LlamaCppPort.DEFAULT)
    assertEquals(1, LlamaCppPort.parse("1"))
    assertEquals(65535, LlamaCppPort.parse("65535"))
    assertFailsWith<IllegalArgumentException> { LlamaCppPort.parse("0") }
    assertFailsWith<IllegalArgumentException> { LlamaCppPort.parse("65536") }
    assertFailsWith<IllegalArgumentException> { LlamaCppPort.parse("abc") }
}
```

- [ ] **Step 2: Run the focused tests and verify they fail because the new types do not exist.**

Run: `./gradlew :server:test --tests de.aidadventure.server.model.ApiModelsTest --tests de.aidadventure.server.llamacpp.LlamaCppPortTest`

Expected: compilation/test failure referring to missing `ModelProvider` or `LlamaCppPort`.

- [ ] **Step 3: Implement the minimal provider model and parser.**

```kotlin
@Serializable
enum class ModelProvider { OLLAMA, LLAMA_CPP }

@Serializable
data class TurnRequest(
    val modelId: String,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
    val provider: ModelProvider = ModelProvider.OLLAMA,
    val llamaCppPort: Int? = null,
)
```

```kotlin
object LlamaCppPort {
    const val DEFAULT = 8081

    fun parse(value: String): Int = value.toIntOrNull()
        ?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("llama.cpp port must be between 1 and 65535.")
}
```

- [ ] **Step 4: Run the focused tests and the existing server tests.**

Run: `./gradlew :server:test --tests de.aidadventure.server.model.ApiModelsTest --tests de.aidadventure.server.llamacpp.LlamaCppPortTest --tests de.aidadventure.server.ApplicationTest`

Expected: all selected tests pass and existing Ollama route tests remain green.

- [ ] **Step 5: Commit the wire-model change.**

```bash
git add server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt \
  server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppPort.kt \
  server/src/test/kotlin/de/aidadventure/server/model/ApiModelsTest.kt \
  server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppPortTest.kt
git commit -m "feat(server): add model provider request fields"
```

### Task 2: Implement llama.cpp discovery and streaming gateway

**Files:**
- Test: `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt`
- Create: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt`

**Interfaces:**
- Produce `LlamaCppGateway(httpClient, promptFactory)` with `availableModels(port: Int): List<String>` and `stream(request: TurnRequest): Flow<TurnStreamEvent>`.
- Use `http://localhost:$port/v1/models` for discovery and `http://localhost:$port/v1/chat/completions` for turns.
- Keep `OllamaGateway` and `LangChain4jOllamaGateway` behavior unchanged except for extracting any shared provider interface required by `Application.kt`.

- [ ] **Step 1: Write a local HTTP-server test for discovery and a streaming OpenAI response test.**

Use `com.sun.net.httpserver.HttpServer` bound to an ephemeral localhost port. Return `{"data":[{"id":"local-model"}]}` from `/v1/models`, and return two SSE frames from `/v1/chat/completions`:

```text
data: {"choices":[{"delta":{"content":"Hello"}}]}

data: {"choices":[{"delta":{"content":" world"}}]}

data: [DONE]

```

Assert that discovery returns `listOf("local-model")`, the request path is correct, the request body contains the selected model and ordered messages, and the stream emits `Token("Hello")`, `Token(" world")`, then `Done` with null metrics.

- [ ] **Step 2: Run the gateway test and verify it fails because the gateway does not exist.**

Run: `./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest`

Expected: compilation failure for missing `LlamaCppGateway`.

- [ ] **Step 3: Implement discovery and OpenAI SSE parsing.**

Use a dedicated `HttpClient` request with a short connection/request timeout already supplied by the gateway constructor. Build the request URL only from the integer port and the fixed `localhost` host. Decode `/v1/models` with `ignoreUnknownKeys = true`; reject non-2xx responses and empty `data`.

For turns, call `promptFactory.build(request)`, map `SystemMessage` to `system`, `UserMessage` to `user`, and `AiMessage` to `assistant`, then send:

```json
{"model":"<modelId>","messages":[...],"stream":true}
```

Read the response line by line in `callbackFlow`, ignore keep-alive lines, decode only `data:` lines, emit nonblank `choices[0].delta.content`, close on `[DONE]`, and close with an exception for HTTP failures or JSON error objects. Never log body content.

- [ ] **Step 4: Run the gateway and full server tests.**

Run: `./gradlew :server:test`

Expected: all server tests pass, including the new local HTTP-server tests.

- [ ] **Step 5: Commit the gateway.**

```bash
git add server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt \
  server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt \
  server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt
git commit -m "feat(server): support llama.cpp chat streaming"
```

### Task 3: Expose llama.cpp discovery and provider-aware turns

**Files:**
- Test: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`

**Interfaces:**
- Add `GET /api/llama/models?port=<integer>` returning `ModelsResponse`.
- Add `llamaCppGateway` to `adventureModule` with a local HTTP client by default and injectable gateway for tests.
- Dispatch `POST /api/turn` to Ollama or llama.cpp based on `request.provider`.

- [ ] **Step 1: Add failing route tests.**

Add a fake llama gateway and assert:

```kotlin
val models = client.get("/api/llama/models?port=8081")
assertEquals(HttpStatusCode.OK, models.status)
assertEquals("{"models":["local-model"]}", models.bodyAsText())

val response = client.post("/api/turn") {
    contentType(ContentType.Application.Json)
    setBody(TurnRequest("local-model", "# Scenario", emptyList(), "look", ModelProvider.LLAMA_CPP, 8081))
}
assertEquals(HttpStatusCode.OK, response.status)
assertEquals(1, fakeLlamaGateway.streamCalls)
```

Also assert that a blank, zero, or 65536 port returns HTTP 400 without calling the gateway, and an unavailable discovery call returns HTTP 503.

- [ ] **Step 2: Run the focused application tests and verify they fail.**

Run: `./gradlew :server:test --tests de.aidadventure.server.ApplicationTest`

Expected: compilation or assertion failure because the route and provider dispatch are absent.

- [ ] **Step 3: Add endpoint, validation, and dispatch.**

Inject `LlamaCppGateway` separately from `OllamaGateway`, parse the query parameter with `LlamaCppPort.parse`, and use the existing `ModelsResponse`/`ApiError` shapes. In `/api/turn`, retain all existing request validation, then require `llamaCppPort` for `LLAMA_CPP`; validate the requested model against `llamaCppGateway.availableModels(port)` and collect the selected gateway's stream. Preserve status codes, SSE event names, request-ID-only logging, and the existing Ollama model cache.

- [ ] **Step 4: Run all server tests and inspect the diff.**

Run: `./gradlew :server:test && git diff --check`

Expected: exit code 0, no whitespace errors, and no changes to the deterministic prompt factory.

- [ ] **Step 5: Commit the API integration.**

```bash
git add server/src/main/kotlin/de/aidadventure/server/Application.kt \
  server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt
git commit -m "feat(server): expose llama.cpp provider routes"
```

### Task 4: Persist provider and port in browser sessions

**Files:**
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/persistence/AdventureDatabaseTest.kt`
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/api/TurnRequestMapperTest.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt`

**Interfaces:**
- Produce a serializable client `ModelProvider` matching `OLLAMA` and `LLAMA_CPP`.
- Extend `GameSession` with `provider: ModelProvider = OLLAMA` and `llamaCppPort: Int? = null`.
- Add `suspend fun llamaCppModels(port: Int): List<String>` to `AdventureApi`.
- Make `TurnRequestMapper.map` copy provider and port into the request.

- [ ] **Step 1: Write failing tests for legacy decoding, persistence fields, mapper output, and default port.**

Assert that a session JSON without new fields decodes as Ollama with null port, that a llama.cpp session round-trips provider/model/8081 through the repository serializer, and that mapping a session produces `ModelProvider.LLAMA_CPP` and `llamaCppPort = 8081`. Add `AdventureApiClient.DEFAULT_LLAMA_CPP_PORT = 8081`.

- [ ] **Step 2: Run the focused browser tests and verify they fail.**

Run: `./gradlew :web:jsTest --tests de.aidadventure.web.persistence.AdventureDatabaseTest --tests de.aidadventure.web.api.TurnRequestMapperTest`

Expected: compilation failures for missing provider and port properties.

- [ ] **Step 3: Implement serializable client fields and API calls.**

Keep the existing default `baseUrl`, add `llamaCppModels(port)` using `GET /api/llama/models?port=$port`, decode `ModelsResponse`, and preserve `AdventureApiException` behavior. Serialize the new turn fields with the same names as the server model.

- [ ] **Step 4: Run all browser unit tests.**

Run: `./gradlew :web:jsTest`

Expected: all existing and new browser tests pass.

- [ ] **Step 5: Commit the persistence and API model change.**

```bash
git add web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt \
  web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt \
  web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt \
  web/src/jsTest/kotlin/de/aidadventure/web/persistence/AdventureDatabaseTest.kt \
  web/src/jsTest/kotlin/de/aidadventure/web/api/TurnRequestMapperTest.kt
git commit -m "feat(web): persist llama.cpp session settings"
```

### Task 5: Add the two-stage llama.cpp model-selection UI

**Files:**
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/LlamaCppConnectionStateTest.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Modify: `web/src/jsMain/resources/app.css` only if a style adjustment is required; otherwise keep existing CSS unchanged.

**Interfaces:**
- Produce a small testable state transition helper, for example `LlamaCppConnectionState`, with `portText`, `modelId`, `checked`, and `error`.
- Preserve Ollama's one-click model buttons.
- Make saved llama.cpp sessions return to the connection control before entering `Play`.

- [ ] **Step 1: Write failing pure-state tests.**

Test these transitions without rendering React:

```kotlin
val initial = LlamaCppConnectionState.initial()
assertEquals("8081", initial.portText)
assertTrue(initial.portEditable)

val checked = initial.connected("local-model")
assertEquals("local-model", checked.modelId)
assertFalse(checked.portEditable)
assertTrue(checked.startOnClick)

assertTrue(checked.failed("service unavailable").portEditable)
```

- [ ] **Step 2: Run the focused browser test and verify it fails.**

Run: `./gradlew :web:jsTest --tests de.aidadventure.web.LlamaCppConnectionStateTest`

Expected: compilation failure because the state helper does not exist.

- [ ] **Step 3: Implement the state helper and wire the detail view.**

Add a `llama.cpp` section below the Ollama model list. Bind the port input to state initialized with `8081` for new sessions or the saved session port for a resumed llama.cpp session. On the first button click, parse the port, call `api.llamaCppModels(port)`, store the first returned model, disable the port input, and change the button label to the discovered model name. On the second click, save a new or resumed `GameSession` with `provider = LLAMA_CPP`, the model ID, and the port, then enter `Play`. Errors keep the port editable and render a text-only client-safe message.

Change the saved-session callback so Ollama sessions still resume directly, while llama.cpp sessions select the saved session in the detail view and initialize the connection state from its stored port/model.

- [ ] **Step 4: Run browser tests and compile the production browser bundle.**

Run: `./gradlew :web:jsTest :web:browserProductionWebpack`

Expected: all browser tests pass and the production bundle completes without Kotlin/JS type errors.

- [ ] **Step 5: Commit the UI flow.**

```bash
git add web/src/jsMain/kotlin/de/aidadventure/web/Main.kt \
  web/src/jsTest/kotlin/de/aidadventure/web/LlamaCppConnectionStateTest.kt
git commit -m "feat(web): add llama.cpp connection control"
```

### Task 6: Document and verify the end-to-end flow

**Files:**
- Modify: `README.md`
- Test/verification: server and browser test suites; local `llama-server` process if available.

- [ ] **Step 1: Add exact startup instructions.**

Document that `llama-server` should listen on `127.0.0.1:8081` by default, then give the existing server and browser commands. Explain that the detail view first checks the model and the second click starts the session, and that a stored llama.cpp port can be edited on resume.

- [ ] **Step 2: Run the full automated verification.**

Run: `./gradlew check`

Expected: exit code 0 with server JVM tests, Kotlin/JS tests, and browser compilation passing.

- [ ] **Step 3: Perform live discovery if a llama-server is available.**

With a local server on port 8081, run:

```bash
curl -fsS 'http://localhost:8080/api/llama/models?port=8081'
```

Expected: `{"models":["<local-model>"]}`. If no llama-server is running, report that live discovery was not available rather than treating it as a code failure.

- [ ] **Step 4: Verify the browser flow manually.**

Start `./gradlew :server:run` and `./gradlew :web:browserDevelopmentRun`, open `http://localhost:8088`, import a scenario, enter port `8081`, click the llama.cpp button, confirm the model name appears and the port disables, then click again and send one action. Reload, reopen the saved session, change the port, and confirm the field is editable before reconnecting.

- [ ] **Step 5: Inspect final state before handoff.**

Run:

```bash
git diff --check
git status --short
ss -ltnpe | rg ':8080|:8081|:8088' || true
```

Stop every process started for verification, rerun the listener check, and report the final runtime state explicitly.

- [ ] **Step 6: Commit documentation.**

```bash
git add README.md
git commit -m "docs: explain llama.cpp connection flow"
```

## Self-Review

- Provider, model, and port requirements are covered by Tasks 1, 4, and 5.
- Discovery, turn dispatch, OpenAI-compatible streaming, and safe failures are covered by Tasks 2 and 3.
- Default port `8081` appears in wire validation, browser state, API tests, README instructions, and live verification.
- Backward compatibility is covered by server JSON tests and browser IndexedDB decoding tests.
- Deterministic prompt construction remains outside the changed provider code and is explicitly checked in Task 3.
- No placeholders or unspecified future work are required by any task.

