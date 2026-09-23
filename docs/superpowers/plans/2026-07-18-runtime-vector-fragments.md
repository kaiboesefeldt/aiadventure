# Runtime Vector Fragments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Retrieve one scenario-defined runtime fragment per later turn and inject it invisibly before the player action without breaking llama.cpp prompt-cache reuse.

**Architecture:** Browser IndexedDB keeps raw imported Markdown. A frontend parser derives stripped Markdown and fragment definitions for API requests. A bounded, reconstructible in-memory server registry indexes the match texts with the existing ONNX embedding service and Lucene. It resolves the latest visible player/game-master pair, and the turn stream supplies the result before tokens.

**Tech Stack:** Kotlin/JVM 21, Ktor SSE, kotlinx.serialization, existing ONNX EmbeddingService, Lucene 10, Kotlin/JS, React, IndexedDB, js-yaml 4.1.0.

## Global Constraints

- Keep the raw template including frontmatter in browser IndexedDB; never send frontmatter to the model or persist it on the server.
- No frontmatter equals no definitions. Invalid YAML or invalid fragment shape rejects import.
- Select exactly top 1 without a score threshold; the first turn has no fragment.
- Prompt order: instruction, stripped scenario, chronological history, selected hidden fragment, current action.
- Persist a hidden fragment immediately before its triggering player message and never render it.
- Prefill and turn resolve the same fragment, session ID, and llama.cpp slot.
- Retrieval errors continue without a fragment and warn only with request ID, operation, and failure class.
- Never log scenario, fragment definitions, history, action, LLM text, model ID, or raw session ID.
- Final verification is ./gradlew check without exclusions.

---

### Task 1: Parse valid frontmatter while retaining the original scenario

**Files:**

- Modify: web/build.gradle.kts
- Create: web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt
- Modify: web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioImport.kt
- Test: web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt
- Modify: web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioImportTest.kt

**Interfaces:**

- Produces RuntimeFragmentDefinition(matchText: String, fragment: String).
- Produces ParsedScenario(promptMarkdown: String, runtimeFragments: List<RuntimeFragmentDefinition>).
- Produces ScenarioFrontmatter.parse(markdown: String): ParsedScenario.

- [ ] **Step 1: Write failing parser and import tests**

~~~
@Test fun parsesFragmentsAndStripsFrontmatter() {
    val parsed = ScenarioFrontmatter.parse("---\nruntime-fragments:\n  lighthouse: Mira remembers it\n---\n# Harbor")
    assertEquals("# Harbor", parsed.promptMarkdown)
    assertEquals(listOf(RuntimeFragmentDefinition("lighthouse", "Mira remembers it")), parsed.runtimeFragments)
}

@Test fun invalidFragmentsRejectImportAndValidRawTemplateRemains() {
    assertEquals(ImportResult.Rejected("Invalid scenario frontmatter."), ScenarioImport.fromFile("x.md", "---\nruntime-fragments: []\n---\n# X", emptyList(), 1))
    val created = assertIs<ImportResult.Created>(ScenarioImport.fromFile("x.md", "---\nruntime-fragments: {}\n---\n# X", emptyList(), 1))
    assertTrue(created.scenario.markdown.startsWith("---"))
}
~~~

- [ ] **Step 2: Run the focused test and verify failure**

Run: ./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --console=plain

Expected: FAIL because parser and types do not exist.

- [ ] **Step 3: Implement strict YAML parsing**

Add implementation(npm("js-yaml", "4.1.0")). Bind only js-yaml.load in ScenarioFrontmatter.kt. Recognize frontmatter only at byte zero with opening and closing --- delimiter lines. Require root and runtime-fragments to be mappings, and require every mapping key and fragment value to be nonblank strings. Reject all other shapes. Return the original body after the closing delimiter. ScenarioImport validates by parsing and maps an error to exactly Invalid scenario frontmatter. It still persists Scenario.markdown unchanged.

~~~
data class RuntimeFragmentDefinition(val matchText: String, val fragment: String)
data class ParsedScenario(val promptMarkdown: String, val runtimeFragments: List<RuntimeFragmentDefinition>)
object ScenarioFrontmatter {
    fun parse(markdown: String): ParsedScenario {
        val delimiterEnd = closingFrontmatterDelimiter(markdown) ?: return ParsedScenario(markdown, emptyList())
        return ParsedScenario(markdown.substring(delimiterEnd).removePrefix("\n"), readRuntimeFragments(markdown.substring(0, delimiterEnd)))
    }
}
~~~

- [ ] **Step 4: Run parser/import tests**

Run: ./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --tests de.aidadventure.web.persistence.ScenarioImportTest --console=plain

Expected: PASS; a template without frontmatter gives full Markdown and an empty list.

- [ ] **Step 5: Commit**

~~~
git add web/build.gradle.kts web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioImport.kt web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioImportTest.kt
git commit -m "feat(web): parse runtime fragment frontmatter"
~~~

### Task 2: Extend requests and prompt history for hidden fragments

**Files:**

- Modify: server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt
- Modify: server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt
- Modify: server/src/main/kotlin/de/aidadventure/server/Application.kt
- Modify: web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt
- Modify: server/src/test/kotlin/de/aidadventure/server/model/ApiModelsTest.kt
- Modify: server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt
- Modify: web/src/jsTest/kotlin/de/aidadventure/web/api/RequestBodyEncodingTest.kt

**Interfaces:**

- Adds serializable RuntimeFragmentDefinition and runtimeFragments: List<RuntimeFragmentDefinition> = emptyList() to warmup, prefill, and turn DTOs.
- Accepts one new history role: runtime-fragment.
- Adds TurnStreamEvent.Fragment(fragment: String) and StreamFragment(fragment: String).

- [ ] **Step 1: Write failing DTO and prompt tests**

~~~
@Test fun runtimeFragmentIsEncodedInTurnRequest() {
    val json = Json.parseToJsonElement(encodeTurnRequest(TurnRequest("# S", emptyList(), "go", "live", listOf(RuntimeFragmentDefinition("m", "f")))).jsonObject
    assertEquals("f", json["runtimeFragments"]!!.jsonArray.single().jsonObject["fragment"]!!.jsonPrimitive.content)
}

@Test fun runtimeFragmentHistoryMapsToSystemMessage() {
    val prompt = PromptFactory().build(TurnRequest("live", "m", 8081, "# S", listOf(ChatEntry("runtime-fragment", "keep quiet")), "go"))
    assertEquals("keep quiet", (prompt[2] as SystemMessage).text())
}
~~~

- [ ] **Step 2: Run focused tests and verify failure**

Run: ./gradlew --no-daemon :server:test --tests de.aidadventure.server.game.PromptFactoryTest --tests de.aidadventure.server.model.ApiModelsTest :web:browserTest --tests de.aidadventure.web.api.RequestBodyEncodingTest --console=plain

Expected: FAIL because fields and role do not exist.

- [ ] **Step 3: Implement contract and validation**

Add the field with a default to server and web DTOs. The three web request mappers call ScenarioFrontmatter.parse(scenario.markdown) and use promptMarkdown and runtimeFragments. PromptFactory maps runtime-fragment to SystemMessage.from. RequestValidator allows the role, caps definitions at 200, caps fields at MAX_TEXT_LENGTH, and rejects duplicate match text. Define the fragment stream payload but do not emit it yet.

- [ ] **Step 4: Run focused tests**

Run: ./gradlew --no-daemon :server:test --tests de.aidadventure.server.game.PromptFactoryTest --tests de.aidadventure.server.model.ApiModelsTest :web:browserTest --tests de.aidadventure.web.api.RequestBodyEncodingTest --console=plain

Expected: PASS.

- [ ] **Step 5: Commit**

~~~
git add server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt server/src/main/kotlin/de/aidadventure/server/Application.kt web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt server/src/test/kotlin/de/aidadventure/server/model/ApiModelsTest.kt server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt web/src/jsTest/kotlin/de/aidadventure/web/api/RequestBodyEncodingTest.kt
git commit -m "feat: add runtime fragment API contract"
~~~

### Task 3: Construct bounded indexes and resolve deterministic top 1

**Files:**

- Create: server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentIndexRegistry.kt
- Create: server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolver.kt
- Test: server/src/test/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentIndexRegistryTest.kt
- Test: server/src/test/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolverTest.kt

**Interfaces:**

~~~
interface RuntimeFragmentResolver {
    suspend fun prepare(definitions: List<RuntimeFragmentDefinition>)
    suspend fun resolve(history: List<ChatEntry>, definitions: List<RuntimeFragmentDefinition>): String?
}
~~~

- [ ] **Step 1: Write failing resolver/lifecycle tests**

~~~
@Test fun resolverEmbedsLatestVisiblePairAndReturnsTopFragment() = runTest {
    assertEquals("Mira tells the story", resolver(top = "Mira tells the story").resolve(listOf(ChatEntry("player", "lighthouse"), ChatEntry("game-master", "sunset")), definitions()))
    assertEquals("lighthouse\nsunset", embedding.inputs.single())
}

@Test fun firstTurnReturnsNullAndHiddenHistoryIsIgnored() = runTest {
    assertNull(resolver().resolve(listOf(ChatEntry("player", "look")), definitions()))
    assertEquals("f", resolver(top = "f").resolve(listOf(ChatEntry("player", "a"), ChatEntry("game-master", "b"), ChatEntry("runtime-fragment", "old")), definitions()))
}
~~~

- [ ] **Step 2: Run focused tests and verify failure**

Run: ./gradlew --no-daemon :server:test --tests de.aidadventure.server.runtimefragments.* --console=plain

Expected: FAIL because registry and resolver do not exist.

- [ ] **Step 3: Implement the transient registry/resolver**

Create in-memory stores with ByteBuffersDirectory, LuceneIndexAccess, QueryOnRegistry, and LuceneQueryPlanner(maxResults = 1); never use the disk-backed application module. SHA-256 fingerprint the ordered definitions and derive stable document IDs from each match/fragment pair. Guard construction with a mutex. Evict and close least-recently-used indexes after 15 idle minutes or above 32 entries. Embed each match text once per live fingerprint. Query with lastPlayer plus newline plus lastGameMaster after filtering runtime-fragment entries; return only the top result, using stable document ID to break ties.

- [ ] **Step 4: Run focused tests**

Run: ./gradlew --no-daemon :server:test --tests de.aidadventure.server.runtimefragments.RuntimeFragmentIndexRegistryTest --tests de.aidadventure.server.runtimefragments.RuntimeFragmentResolverTest --console=plain

Expected: PASS; same definitions reuse one index, first turn returns null, hidden history does not change the query.

- [ ] **Step 5: Commit**

~~~
git add server/src/main/kotlin/de/aidadventure/server/runtimefragments server/src/test/kotlin/de/aidadventure/server/runtimefragments
git commit -m "feat(server): resolve transient runtime fragments"
~~~

### Task 4: Resolve once for a cacheable request and emit context before tokens

**Files:**

- Modify: server/src/main/kotlin/de/aidadventure/server/Application.kt
- Modify: server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt
- Modify: server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt
- Modify: server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt

**Interfaces:**

- adventureModule receives runtimeFragmentResolver: RuntimeFragmentResolver with a production default.
- ResolvedTurn(request: TurnRequest, fragment: String?) is the one-resolution handoff.

- [ ] **Step 1: Write failing API/cache tests**

~~~
@Test fun turnStreamsSelectedFragmentBeforeToken() = testApplication {
    application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolver("keep quiet")) }
    assertContains(client.post("/api/turn") { setBody(turnRequestWithVisiblePair()) }.bodyAsText(), "event: fragment\ndata: {\"fragment\":\"keep quiet\"}\n\nevent: token")
}
~~~

Add a coordinator test proving prefill then turn receive one enriched request and the same fake slot.

- [ ] **Step 2: Run focused tests and verify failure**

Run: ./gradlew --no-daemon :server:test --tests de.aidadventure.server.ApplicationTest --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --console=plain

Expected: FAIL because resolver/SSE fragment wiring does not exist.

- [ ] **Step 3: Implement resolution and fallback**

Warmup calls prepare(definitions) but keeps its prompt unchanged. Prefill and turn resolve once; on exceptions warn only requestId, operation, and exception simple name then use no fragment. Append a non-null fragment as ChatEntry(runtime-fragment, fragment) to the gateway-only history immediately before the current action. Store the enriched request in coordinator state so the prefill-to-turn path cannot resolve again. The SSE adapter emits StreamFragment before collecting tokens. Preserve existing error mapping, revisions, lease retention, and slot ownership.

- [ ] **Step 4: Run focused tests**

Run: ./gradlew --no-daemon :server:test --tests de.aidadventure.server.ApplicationTest --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --console=plain

Expected: PASS; fallback streams normally and fragment precedes tokens.

- [ ] **Step 5: Commit**

~~~
git add server/src/main/kotlin/de/aidadventure/server/Application.kt server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt
git commit -m "feat(server): inject runtime fragments into turns"
~~~

### Task 5: Persist the hidden stream event in the correct position

**Files:**

- Modify: web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt
- Modify: web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt
- Modify: web/src/jsMain/kotlin/de/aidadventure/web/Main.kt
- Modify: web/src/jsMain/kotlin/de/aidadventure/web/PrefillSubmission.kt
- Test: web/src/jsTest/kotlin/de/aidadventure/web/TurnSubmissionTest.kt
- Modify: web/src/jsTest/kotlin/de/aidadventure/web/PrefillSubmissionTest.kt
- Modify: web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt

**Interfaces:**

~~~
suspend fun AdventureApi.play(request: TurnRequest, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnMetrics
~~~

- [ ] **Step 1: Write failing ordering/visibility tests**

~~~
@Test fun completedTurnStoresFragmentBeforePendingPlayer() = runTest {
    api.emitFragment("Mira warns the player")
    val completed = submission.submit(sessionWithPreviousPair(), scenario(), "ask about lighthouse", {}, {})
    assertEquals(listOf("player", "game-master", "runtime-fragment", "player", "game-master"), completed.session.messages.map(GameMessage::role))
}

@Test fun runtimeFragmentIsExcludedFromTranscript() {
    assertFalse(isVisibleTranscriptMessage(GameMessage("runtime-fragment", "secret", 1)))
}
~~~

- [ ] **Step 2: Run browser tests and verify failure**

Run: ./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.TurnSubmissionTest --tests de.aidadventure.web.PrefillSubmissionTest --tests de.aidadventure.web.AdventureAppNavigationTest --console=plain

Expected: FAIL because stream decoding and insertion do not exist.

- [ ] **Step 3: Implement stream decode, persistence, and UI filtering**

Decode fragment frames and call onFragment. TurnSubmission creates the pending player entry once; when the fragment arrives, it replaces the tail with runtime-fragment followed by player, then reuses that state for streamed and completed messages. Persist only after success; on failed turn keep only the visible pending player state. PlayView filters message.role != runtime-fragment before creating transcript elements. All request mappers use the parsed scenario from Task 2.

- [ ] **Step 4: Run browser verification**

Run: ./gradlew --no-daemon :web:compileTestKotlinJs :web:browserTest :web:test --console=plain

Expected: PASS; raw template remains stored, requests are stripped, hidden records persist in order, and UI omits them.

- [ ] **Step 5: Commit**

~~~
git add web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt web/src/jsMain/kotlin/de/aidadventure/web/Main.kt web/src/jsMain/kotlin/de/aidadventure/web/PrefillSubmission.kt web/src/jsTest/kotlin/de/aidadventure/web/TurnSubmissionTest.kt web/src/jsTest/kotlin/de/aidadventure/web/PrefillSubmissionTest.kt web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt
git commit -m "feat(web): persist hidden runtime fragments"
~~~

### Task 6: Document and prove cache behavior end to end

**Files:**

- Modify: README.md
- Modify: docs/llama-cpp-prefill-verification.md
- Modify: docs/superpowers/specs/2026-07-18-runtime-vector-fragments-design.md

- [ ] **Step 1: Add a privacy-safe acceptance checklist**

Document a template example with frontmatter, first-turn no-fragment behavior, selected later fragment invisible in UI, reload/resume, and a following turn. Record only request IDs, slot identity, tokens_cached, and tokens_evaluated.

- [ ] **Step 2: Run automated verification**

Run: ./gradlew check --console=plain

Expected: PASS.

- [ ] **Step 3: Run live proof and clean up**

Start llama.cpp and ./gradlew :server:run --args='--llama-port=8081'. Import the documented example, complete two turns, reload/resume, then complete another turn. Verify a retained slot and backend metrics. Stop every started process.

Expected: hidden fragments never appear visibly; later turns show llama.cpp cache reuse.

- [ ] **Step 4: Commit**

~~~
git add README.md docs/llama-cpp-prefill-verification.md docs/superpowers/specs/2026-07-18-runtime-vector-fragments-design.md
git commit -m "docs: verify runtime fragment cache flow"
~~~

## Plan self-review

- Spec coverage: Tasks 1 and 5 cover frontmatter and IndexedDB; Task 2 covers DTO/prompt role; Task 3 supplies transient top-1 retrieval; Task 4 covers fallback, coordinator, and SSE order; Task 6 provides cache proof and docs.
- Placeholder scan: all tasks name exact files, tests, commands, expected outcomes, implementation rules, and commits.
- Type consistency: RuntimeFragmentDefinition, runtime-fragment, RuntimeFragmentResolver, and StreamFragment are each introduced before consumers.
