# Stream Ollama Tokens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream each generated Ollama text fragment through `POST /api/turn` and render it immediately in the browser.

**Architecture:** The server gateway exposes a `Flow<TurnStreamEvent>` backed by
LangChain4j's `OllamaStreamingChatModel`. Ktor encodes that flow as a POST SSE
response. The Kotlin/JS API client reads its response channel line-by-line and
uses a small SSE frame decoder; `TurnSubmission` owns the transient message and
persists it only after the terminal `done` frame.

**Tech Stack:** Kotlin/JVM 21, Kotlin/JS, Ktor 3.4.1, LangChain4j Ollama 1.17.2, Kotlin coroutines Flow, React, Kotlin test.

## Global Constraints

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Set Ollama `think(false)` for every streamed turn and never forward thinking text.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
- Treat generated text as untrusted and render it as text nodes only.
- Log only request ID, operation, result, and failure class; never log scenario, history, actions, or LLM text.
- `POST /api/turn` emits `token`, `done`, and in-stream `error` SSE events on successful streaming responses.

---

### Task 1: Stream Ollama callbacks through the server gateway and SSE route

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`

**Interfaces:**
- Consumes: `PromptFactory.build(request)` and LangChain4j `StreamingChatResponseHandler`.
- Produces: `fun OllamaGateway.stream(request: TurnRequest): Flow<TurnStreamEvent>` and SSE frames named `token`, `done`, and `error`.

- [ ] **Step 1: Write the failing SSE route tests**

Replace the fake gateway's `play` method with a `stream` method that returns
`flowOf(TurnStreamEvent.Token("The "), TurnStreamEvent.Token("door opens."), TurnStreamEvent.Done(TurnMetrics(null, 12, null)))`.
Add this test to `ApplicationTest`:

```kotlin
@Test
fun `turn streams token frames and terminal metrics`() = testApplication {
    application { adventureModule(FakeGateway()) }
    configureJsonClient()

    val response = client.post("/api/turn") {
        contentType(ContentType.Application.Json)
        setBody(TurnRequest("model", "# Scenario", emptyList(), "look"))
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
    assertEquals(
        "event: token\\ndata: {\"text\":\"The \"}\\n\\n" +
            "event: token\\ndata: {\"text\":\"door opens.\"}\\n\\n" +
            "event: done\\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":12,\"cacheTokens\":null}}\\n\\n",
        response.bodyAsText(),
    )
}
```

Add a second test whose fake flow throws `IllegalStateException("offline")` after
one token. Assert `200 OK` and an `event: error` frame containing only
`{"code":"ollama_unavailable","message":"The model service is unavailable."}`.

- [ ] **Step 2: Run the server test to verify it fails**

Run: `./gradlew :server:test --tests de.aidadventure.server.ApplicationTest`

Expected: test compilation fails because `TurnStreamEvent` and
`OllamaGateway.stream` do not yet exist.

- [ ] **Step 3: Introduce streamed gateway events and the LangChain4j bridge**

In `ApiModels.kt`, replace the unused server `TurnResponse` with:

```kotlin
sealed interface TurnStreamEvent {
    data class Token(val text: String) : TurnStreamEvent
    data class Done(val metrics: TurnMetrics) : TurnStreamEvent
}

@Serializable data class StreamToken(val text: String)
@Serializable data class StreamDone(val metrics: TurnMetrics)
@Serializable data class StreamError(val code: String, val message: String)
```

Change `OllamaGateway` to:

```kotlin
interface OllamaGateway {
    fun availableModels(): List<String>
    fun stream(request: TurnRequest): Flow<TurnStreamEvent>
}
```

Implement `LangChain4jOllamaGateway.stream` with `callbackFlow`. Build an
`OllamaStreamingChatModel` using the same base URL, requested model ID, and
`defaultRequestParameters(keepAliveSeconds)`. Its handler calls
`trySend(TurnStreamEvent.Token(partialResponse))` from
`onPartialResponse`, sends `TurnStreamEvent.Done` with input-token usage in
`onCompleteResponse`, closes after the done event, and closes with the received
exception in `onError`. Do not implement `onPartialThinking`.

- [ ] **Step 4: Encode the flow as SSE in `POST /api/turn`**

Keep all current validation, model catalog, and pre-stream HTTP error branches.
After the model is known, replace `call.respond(gateway.play(request))` with
`call.respondTextWriter(contentType = ContentType.Text.EventStream)`. Collect
the gateway flow and write, then flush, exactly one frame per event:

```kotlin
fun Writer.sse(event: String, data: String) {
    write("event: $event\\ndata: $data\\n\\n")
    flush()
}
```

Define `private val eventJson = Json { encodeDefaults = true }` beside the
route helpers. Use `eventJson.encodeToString(StreamToken(text))` for tokens and
`eventJson.encodeToString(StreamDone(metrics))` for completion. Catch exceptions
from collection inside the text writer, log
`requestId={} operation=turn result=ollama_unavailable cause={}`, and emit one
`error` frame with `StreamError("ollama_unavailable", "The model service is unavailable.")`.

- [ ] **Step 5: Run the server tests to verify they pass**

Run: `./gradlew :server:test --tests de.aidadventure.server.ApplicationTest`

Expected: Gradle exits with code 0; existing validation/model-catalog tests and
the new token, done, and error-frame tests pass.

- [ ] **Step 6: Commit the server stream**

```bash
git add server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt \
  server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt \
  server/src/main/kotlin/de/aidadventure/server/Application.kt \
  server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt
git commit -m "feat(server): stream Ollama turn tokens"
```

### Task 2: Decode SSE frames in the browser API client

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Create: `web/src/jsTest/kotlin/de/aidadventure/web/api/SseFrameDecoderTest.kt`

**Interfaces:**
- Consumes: SSE frames produced by Task 1 and existing `TurnRequest` JSON.
- Produces: `suspend fun AdventureApi.play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics`.

- [ ] **Step 1: Write the failing SSE decoder tests**

Create `SseFrameDecoderTest.kt`:

```kotlin
class SseFrameDecoderTest {
    @Test
    fun `decoder emits a token only after its blank-line terminator`() {
        val decoder = SseFrameDecoder()

        assertEquals(emptyList(), decoder.push("event: token\\ndata: {\"text\":\"The "))
        assertEquals(listOf(SseFrame("token", "{\"text\":\"The door\"}")), decoder.push("door\"}\\n\\n"))
    }

    @Test
    fun `decoder accepts CRLF framed done events`() {
        val decoder = SseFrameDecoder()

        assertEquals(
            listOf(SseFrame("done", "{\"metrics\":{\"promptTokens\":3}}")),
            decoder.push("event: done\\r\\ndata: {\"metrics\":{\"promptTokens\":3}}\\r\\n\\r\\n"),
        )
    }
}
```

- [ ] **Step 2: Run the browser decoder test to verify it fails**

Run: `./gradlew :web:browserTest --tests de.aidadventure.web.api.SseFrameDecoderTest`

Expected: test compilation fails because `SseFrameDecoder` and `SseFrame` do not yet exist.

- [ ] **Step 3: Add incremental response decoding and event dispatch**

In `AdventureApi.kt`, change the interface method to:

```kotlin
suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics
```

Implement `SseFrameDecoder` with a retained string buffer. `push(chunk)`
normalizes `\r\n` to `\n`, removes every complete double-newline-delimited
frame, extracts the `event:` and `data:` lines, and returns `SseFrame(event,
data)` values. Leave incomplete frame text in the buffer.

In `AdventureApiClient.play`, retain the JSON POST request but obtain
`response.bodyAsChannel()`. Repeatedly call `readUTF8Line()` and feed each line
plus `\n` into the decoder. Decode `token` data as `StreamToken` and call
`onToken(token.text)`. Decode `done` data as `StreamDone` and return its
metrics. Decode `error` data as `StreamError` and throw
`AdventureApiException(error.message)`. If the channel closes without a done
frame, throw `AdventureApiException("The model response ended unexpectedly.")`.

Add browser-local serializable `StreamToken`, `StreamDone`, and `StreamError`
types matching the server event data, because the JVM and JS modules do not
share a model artifact.

- [ ] **Step 4: Run the decoder test to verify it passes**

Run: `./gradlew :web:browserTest --tests de.aidadventure.web.api.SseFrameDecoderTest`

Expected: Gradle exits with code 0 and both fragmented- and CRLF-frame tests pass.

- [ ] **Step 5: Commit the browser stream transport**

```bash
git add web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt \
  web/src/jsTest/kotlin/de/aidadventure/web/api/SseFrameDecoderTest.kt
git commit -m "feat(web): decode streamed turn events"
```

### Task 3: Render partial game-master text and persist only completed turns

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/TurnSubmissionTest.kt`

**Interfaces:**
- Consumes: `AdventureApi.play(request, onToken)` from Task 2.
- Produces: `TurnSubmission.submit(..., onStarted, onStreamed)` updates the live session for each token and returns a persistable `CompletedTurn` only after `done`.

- [ ] **Step 1: Write the failing incremental submission tests**

Replace the fake API in `TurnSubmissionTest` with the new method signature.
Add a test whose fake API calls `onToken("The ")`, then `onToken("door opens.")`,
and returns `TurnMetrics(null, 12, null)`. Collect every `onStreamed` session
and every saved session. Assert that the first streamed assistant content is
`"The "`, the second is `"The door opens."`, and that only the final saved
session contains the game-master message.

Add a second test whose fake API emits `"The "` then throws
`AdventureApiException("The model service is unavailable.")`. Assert that the
last `onStreamed` value equals the started player-only session and that no saved
session includes a game-master message.

- [ ] **Step 2: Run the submission test to verify it fails**

Run: `./gradlew :web:browserTest --tests de.aidadventure.web.TurnSubmissionTest`

Expected: test compilation fails because `TurnSubmission.submit` has no
stream-update callback and `AdventureApi.play` has the old signature.

- [ ] **Step 3: Implement transient session updates**

Change `TurnSubmission.submit` to accept `onStreamed: (GameSession) -> Unit`.
After saving the started player message, accumulate each token in `streamedText`
and call `onStreamed` with `started.copy(messages = started.messages +
GameMessage("game-master", streamedText, clock()), updatedAt = clock())`.
After `AdventureApi.play` returns, save and return that completed session. In a
`catch` around `api.play`, call `onStreamed(started)` before rethrowing so the
temporary assistant message disappears.

Call the two-argument `api.play(TurnRequestMapper.map(...), onToken)` overload
directly. After this is the only production caller, remove the temporary
one-argument `AdventureApi.play(request): TurnResponse` adapter added in Task 2
and remove the client-only `TurnResponse` type it used.

In `Main.kt`, pass `props.onSessionChanged` as both `onStarted` and
`onStreamed`. Keep the existing `pending` lifecycle: it remains true until the
stream returns or throws, then the UI either shows final metrics or the safe
error message.

- [ ] **Step 4: Run the submission test to verify it passes**

Run: `./gradlew :web:browserTest --tests de.aidadventure.web.TurnSubmissionTest`

Expected: Gradle exits with code 0; progressive rendering, final-only
persistence, and failure cleanup pass.

- [ ] **Step 5: Update the smoke-test documentation and run full verification**

In `README.md`, state that a successful `POST /api/turn` is an SSE response
with `token` frames followed by `done`; provide this direct stream check:

```bash
curl -N -X POST http://localhost:8080/api/turn \
  -H 'Content-Type: application/json' \
  --data '{"modelId":"YOUR_MODEL","scenarioMarkdown":"# Fog Harbor","history":[],"playerAction":"look"}'
```

Run: `./gradlew check`

Expected: Gradle exits with code 0; both server and browser test suites pass.

- [ ] **Step 6: Commit the progressive UI**

```bash
git add web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt \
  web/src/jsMain/kotlin/de/aidadventure/web/Main.kt \
  web/src/jsTest/kotlin/de/aidadventure/web/TurnSubmissionTest.kt README.md
git commit -m "feat(web): render streamed game-master turns"
```
