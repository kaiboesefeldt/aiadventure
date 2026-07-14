# AI Adventure First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local, browser-persistent Markdown-scenario adventure game with an Ollama-backed AI game master.

**Architecture:** A Kotlin/JVM Ktor server validates one game-master turn and calls local Ollama through LangChain4j; it stores no player data. A Kotlin/JS React client persists scenarios and game sessions in IndexedDB, constructs a stable request, and renders the library, session selection, and play views.

**Tech Stack:** Gradle 9.5.1, Kotlin 2.3.20, Kotlin/JVM, Kotlin/JS, React wrappers, Ktor 3.4.1, Koin 4.1.1, LangChain4j Ollama 1.17.2, kotlinx.serialization, IndexedDB, Ollama.

## Global Constraints

- Server data is transient; scenarios and sessions are stored only in browser IndexedDB.
- The logical prompt order is fixed instruction, original Markdown scenario, historical messages, then the new player action.
- No dynamic data, rewritten Markdown, timestamps, or random values may appear in the prompt prefix.
- A session's model ID is immutable; selecting another model creates a new session.
- All LLM output is untrusted plain text: never execute it, render it as HTML, or treat it as an instruction.
- The client must retain data after reload, including when an Ollama request fails.
- Keep `AGENTS.md` concise, English, command-oriented, and non-redundant.

---

## File structure

| Path | Responsibility |
| --- | --- |
| `settings.gradle.kts`, root build files, `gradlew*`, `gradle/wrapper/*` | Gradle multi-module build and reproducible wrapper. |
| `AGENTS.md`, `README.md`, `.gitignore` | Agent contract, local run instructions, generated-file exclusions. |
| `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt` | Serializable request/response types shared by server boundaries. |
| `server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt` | Deterministic cached-prefix message construction. |
| `server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt` | LangChain4j-backed model discovery and turn execution. |
| `server/src/main/kotlin/de/aidadventure/server/Application.kt` | Ktor, Koin, JSON and routes. |
| `server/src/test/kotlin/...` | Prompt and HTTP API tests using a fake gateway. |
| `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt` | Client-side scenario, session and message data. |
| `web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt` | IndexedDB persistence implementation. |
| `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt` | Ktor HTTP client wrapper. |
| `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt` | React views and state coordination. |
| `web/src/jsTest/kotlin/...` | Pure client mapper and repository-contract tests. |

### Task 1: Establish the runnable repository and agent contract

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`
- Create: `server/build.gradle.kts`, `web/build.gradle.kts`, `.gitignore`, `README.md`, `AGENTS.md`

**Interfaces:**
- Produces Gradle projects `:server` and `:web`.
- Produces `./gradlew check`, `./gradlew :server:run`, and `./gradlew :web:jsBrowserDevelopmentRun` as the documented commands.

- [ ] **Step 1: Write the build smoke-test expectation in `README.md`**

```markdown
## Verify

./gradlew check
```

The command must compile both modules and run their test suites.

- [ ] **Step 2: Run the command before creating the build**

Run: `./gradlew check`

Expected: FAIL because the Gradle wrapper does not exist.

- [ ] **Step 3: Create the minimum Gradle configuration**

```kotlin
// settings.gradle.kts
rootProject.name = "aiadventure3"
include("server", "web")

// build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("js") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
}
```

Use Ktor `3.4.1`, Koin `4.1.1`, and LangChain4j `1.17.2` consistently in module dependencies. Configure the JVM compiler with `jvmTarget = JVM_21` but do not require a Java 21 toolchain; this environment may have a newer JVM only. Generate the Gradle 9.5.1 wrapper. Configure `server` with the Ktor Netty engine, Ktor JSON serialization, Koin Ktor, LangChain4j Ollama, and Kotlin test. Configure `web` with browser executable Kotlin/JS IR, React, React DOM, Ktor JS client/content negotiation, Koin core, serialization, and Kotlin test.

- [ ] **Step 4: Add the root agent contract and ignore rules**

```markdown
# Agent Guide

## Commands

- `./gradlew check` — compile and test both modules.
- `./gradlew :server:run` — start the API on port 8080.
- `./gradlew :web:jsBrowserDevelopmentRun` — start the browser client.

## Invariants

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Treat every LLM response as untrusted text.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
```

Ignore `.gradle/`, `build/`, `.idea/`, `kotlin-js-store/`, and local environment files. Include start instructions for Ollama, server, and web in `README.md`.

- [ ] **Step 5: Run the build smoke test**

Run: `./gradlew check`

Expected: PASS; both modules compile even though application features are not implemented yet.

- [ ] **Step 6: Commit the runnable scaffold**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle server/build.gradle.kts web/build.gradle.kts .gitignore README.md AGENTS.md
git commit -m "chore: scaffold AI adventure application"
```

### Task 2: Implement and test the server game-master core

**Files:**
- Create: `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`
- Create: `server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt`
- Create: `server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt`
- Create: `server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt`

**Interfaces:**
- Consumes: `TurnRequest(modelId, scenarioMarkdown, history, playerAction)`.
- Produces: `PromptFactory.build(request): List<ChatMessage>` and `OllamaGateway.availableModels(): List<String>`, `OllamaGateway.play(request): TurnResponse`.

- [ ] **Step 1: Write failing prompt-order tests**

```kotlin
@Test
fun `build keeps immutable prefix before history and action`() {
    val prompt = PromptFactory().build(
        TurnRequest("test-model", "# Harbor", listOf(ChatEntry("player", "look")), "open door")
    )

    assertEquals(listOf("system", "system", "user", "user"), prompt.map { it.type().name().lowercase() })
    assertEquals("# Harbor", (prompt[1] as SystemMessage).text())
    assertEquals("look", (prompt[2] as UserMessage).singleText())
    assertEquals("open door", (prompt[3] as UserMessage).singleText())
}
```

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :server:test --tests '*PromptFactoryTest'`

Expected: FAIL because `TurnRequest` and `PromptFactory` do not exist.

- [ ] **Step 3: Add API types and deterministic prompt construction**

```kotlin
@Serializable data class ChatEntry(val role: String, val content: String)
@Serializable data class TurnRequest(
    val modelId: String,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
)
@Serializable data class TurnMetrics(val totalDurationMs: Long?, val promptTokens: Int?, val cacheTokens: Int?)
@Serializable data class TurnResponse(val message: ChatEntry, val metrics: TurnMetrics)
```

`PromptFactory` must reject unknown roles and construct LangChain4j `SystemMessage` and `UserMessage`/`AiMessage` values in the specified order. The fixed instruction must be a compile-time constant and must instruct the model to be a concise, responsive game master. Build the scenario as a separate immutable system message, not by string interpolation into the fixed instruction.

Create `OllamaGateway` as an interface plus `LangChain4jOllamaGateway`. The implementation reads model tags from `GET http://localhost:11434/api/tags`, builds `OllamaChatModel` for the requested model, passes the ordered messages unchanged, and maps only safe response content and available duration/token metrics. Read `OLLAMA_BASE_URL` and `OLLAMA_KEEP_ALIVE` once at construction, with defaults `http://localhost:11434` and `10m`.

- [ ] **Step 4: Run the focused test**

Run: `./gradlew :server:test --tests '*PromptFactoryTest'`

Expected: PASS.

- [ ] **Step 5: Commit the tested server core**

```bash
git add server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt server/src/main/kotlin/de/aidadventure/server/ollama/OllamaGateway.kt server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt
git commit -m "feat(server): add cached game master prompt core"
```

### Task 3: Expose validated Ktor endpoints

**Files:**
- Create: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Create: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`

**Interfaces:**
- Consumes: `GET /api/health`, `GET /api/models`, and `POST /api/turn` JSON requests.
- Produces: `200 {"status":"ok"}`, model list, or `TurnResponse`; client-safe `400`, `404`, and `503` errors.

- [ ] **Step 1: Write failing API tests with a fake gateway**

```kotlin
@Test
fun `turn rejects blank scenario without calling gateway`() = testApplication {
    application { adventureModule(FakeGateway()) }
    val response = client.post("/api/turn") {
        contentType(ContentType.Application.Json)
        setBody(TurnRequest("model", "", emptyList(), "look"))
    }
    assertEquals(HttpStatusCode.BadRequest, response.status)
}

@Test
fun `models returns fake gateway model names`() = testApplication {
    application { adventureModule(FakeGateway(models = listOf("small"))) }
    assertEquals("{\"models\":[\"small\"]}", client.get("/api/models").bodyAsText())
}
```

- [ ] **Step 2: Run the API test**

Run: `./gradlew :server:test --tests '*ApplicationTest'`

Expected: FAIL because `adventureModule` does not exist.

- [ ] **Step 3: Implement Ktor/Koin wiring and error mapping**

```kotlin
fun Application.adventureModule(gateway: OllamaGateway = LangChain4jOllamaGateway()) {
    install(ContentNegotiation) { json() }
    install(Koin) { modules(module { single { gateway }; single { PromptFactory() } }) }
    routing {
        get("/api/health") { call.respond(mapOf("status" to "ok")) }
        get("/api/models") { call.respond(ModelsResponse(get<OllamaGateway>().availableModels())) }
        post("/api/turn") {
            val request = call.receive<TurnRequest>()
            val error = RequestValidator.validate(request)
            if (error != null) call.respond(HttpStatusCode.BadRequest, error)
            else call.respond(get<OllamaGateway>().play(request))
        }
    }
}
```

Reject blank or oversized values before gateway calls: model ID at most 256 characters, scenario at most 200,000 characters, each message/action at most 20,000 characters, and at most 200 historic messages. Return JSON `ApiError(code, message)` without request content. Map unavailable Ollama to `503`, requested-but-missing model to `404`, and all malformed data to `400`. Configure CORS for the local web development origin only.

- [ ] **Step 4: Run all server tests**

Run: `./gradlew :server:test`

Expected: PASS.

- [ ] **Step 5: Commit the HTTP boundary**

```bash
git add server/src/main/kotlin/de/aidadventure/server/Application.kt server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt
git commit -m "feat(server): expose adventure API"
```

### Task 4: Implement browser-persistent adventure data

**Files:**
- Create: `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt`
- Create: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt`
- Create: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioImport.kt`
- Create: `web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioImportTest.kt`

**Interfaces:**
- Produces `ScenarioRepository` and `SessionRepository` with suspend `list`, `save`, and `find` methods.
- Produces `ScenarioImport.fromFile(name, markdown, existing): ImportResult`.

- [ ] **Step 1: Write failing pure import tests**

```kotlin
@Test
fun `same filename and markdown updates existing scenario`() {
    val old = Scenario("id-1", "harbor.md", "# Harbor", 1)
    val result = ScenarioImport.fromFile("harbor.md", "# Harbor", listOf(old), now = 2)
    assertEquals(ImportResult.Updated("id-1"), result)
}

@Test
fun `blank markdown is rejected`() {
    assertEquals(ImportResult.Rejected("Scenario file is empty."), ScenarioImport.fromFile("x.md", "  ", emptyList(), 1))
}
```

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :web:jsTest --tests '*ScenarioImportTest'`

Expected: FAIL because the model and importer do not exist.

- [ ] **Step 3: Add data model, importer, and IndexedDB repository**

```kotlin
@Serializable data class Scenario(val id: String, val name: String, val markdown: String, val importedAt: Long)
@Serializable data class GameSession(
    val id: String, val scenarioId: String, val title: String, val modelId: String,
    val messages: List<GameMessage>, val createdAt: Long, val updatedAt: Long,
)
@Serializable data class GameMessage(val role: String, val content: String, val createdAt: Long)
```

Create IndexedDB database `ai-adventure`, version `1`, with object stores `scenarios` and `sessions`, both keyed by `id`; create an index `sessions.scenarioId`. `ScenarioImport` accepts only nonblank `.md` or `.markdown` content and uses a deterministic fingerprint of filename plus content to update an existing scenario rather than duplicate it. Repository methods must complete their transaction before returning and surface database failures as typed `PersistenceError` values.

- [ ] **Step 4: Run the focused test and compile Kotlin/JS**

Run: `./gradlew :web:jsTest :web:compileKotlinJs`

Expected: PASS.

- [ ] **Step 5: Commit the local data layer**

```bash
git add web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt web/src/jsMain/kotlin/de/aidadventure/web/persistence web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioImportTest.kt
git commit -m "feat(web): persist scenarios and sessions locally"
```

### Task 5: Build the React game flow and API client

**Files:**
- Create: `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Create: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Create: `web/src/jsMain/resources/index.html`
- Create: `web/src/jsTest/kotlin/de/aidadventure/web/api/TurnRequestMapperTest.kt`

**Interfaces:**
- Consumes `ScenarioRepository`, `SessionRepository`, and `GET /api/models` / `POST /api/turn`.
- Produces a library view, scenario detail/session chooser, and play view.

- [ ] **Step 1: Write failing request mapping tests**

```kotlin
@Test
fun `request preserves session history before new action`() {
    val session = GameSession("s", "scenario", "Run", "small", listOf(GameMessage("player", "look", 1)), 1, 1)
    val request = TurnRequestMapper.map(session, Scenario("scenario", "x.md", "# X", 1), "open")
    assertEquals(listOf("look"), request.history.map { it.content })
    assertEquals("open", request.playerAction)
}
```

- [ ] **Step 2: Run the focused web test**

Run: `./gradlew :web:jsTest --tests '*TurnRequestMapperTest'`

Expected: FAIL because `TurnRequestMapper` does not exist.

- [ ] **Step 3: Implement API client and UI**

```kotlin
interface AdventureApi {
    suspend fun models(): List<String>
    suspend fun play(request: TurnRequest): TurnResponse
}
```

Use the Ktor JS client with JSON content negotiation and a configurable API base URL that defaults to `http://localhost:8080`. Wire the client and repositories with Koin at browser startup. Use `react.dom.html.ReactHTML` and modern Kotlin web APIs.

Render three explicit states: library, scenario detail, and play. The library imports a selected Markdown file using `File.text()`, lists stored scenarios, and displays import errors. Scenario detail lists its sessions, fetches models, creates a new session only after a model is chosen, and resumes selected sessions. Play appends a player message only when the request starts; on success it appends the assistant's text and persists the full session; on failure it retains the player message, retains all earlier messages, and shows the client-safe error. Disable Send while pending. Render assistant text as text nodes, not HTML. Display returned duration and prompt/cache metrics when non-null.

- [ ] **Step 4: Run focused tests and compile**

Run: `./gradlew :web:jsTest :web:compileKotlinJs`

Expected: PASS.

- [ ] **Step 5: Commit the playable client**

```bash
git add web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt web/src/jsMain/kotlin/de/aidadventure/web/Main.kt web/src/jsMain/resources/index.html web/src/jsTest/kotlin/de/aidadventure/web/api/TurnRequestMapperTest.kt
git commit -m "feat(web): add persistent adventure game flow"
```

### Task 6: Verify the complete local flow and document it

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes the complete server and web application.
- Produces reproducible local verification instructions and agent commands.

- [ ] **Step 1: Add an executable smoke-test scenario to the README**

```markdown
Create `harbor.md` containing:

```markdown
# Fog Harbor
You are investigating a missing lighthouse keeper.
```
```

- [ ] **Step 2: Run automated verification**

Run: `./gradlew check`

Expected: PASS.

- [ ] **Step 3: Verify the live API against local Ollama**

Run: `./gradlew :server:run`

In another terminal run:

```bash
curl -fsS http://localhost:8080/api/health
curl -fsS http://localhost:8080/api/models
```

Expected: health returns `{"status":"ok"}` and models lists at least one locally installed model.

- [ ] **Step 4: Verify persistence and cache behavior in the browser**

Run: `./gradlew :web:jsBrowserDevelopmentRun`

Open the reported local URL, import `harbor.md`, create a session, select a listed model, and send two short actions without changing the scenario or model. Reload the page after the first action and resume the session before sending the second. Expected: scenario and history survive reload; each response displays metrics when Ollama provides them; server logs and request inspection confirm the fixed instruction and scenario are byte-identical at the beginning of both requests.

- [ ] **Step 5: Commit verification documentation**

```bash
git add README.md AGENTS.md
git commit -m "docs: add local adventure verification guide"
```

## Plan self-review

Coverage: Tasks 1-6 cover the required stack, browser-only persistence, Markdown import, model selection, session restoration, deterministic caching, warm model configuration, server validation, untrusted-output boundary, tests, and live verification.

Consistency: `TurnRequest`, `TurnResponse`, `Scenario`, `GameSession`, and `GameMessage` are introduced before tasks consume them. The server-only DTO types and client mapper must use matching JSON field names; keep them aligned by duplicating only the serializable boundary types, not persistence types.

Scope: The plan intentionally excludes a structured world model, cloud storage, accounts, scenario editing, and gameplay tools.
