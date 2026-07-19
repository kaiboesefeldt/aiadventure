# Runtime Fragment Re-Ranking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Only warm a retrieved runtime fragment after a background relevance score of 5–9, while preserving the canonical cache prefix and retained slot.

**Architecture:** Retrieval returns a full `RuntimeFragmentDefinition`. The coordinator retains its lease to score the candidate key with one token, parse it strictly, then warm a freshly constructed canonical history with the fragment only when accepted.

**Tech Stack:** Kotlin/JVM, Ktor SSE, kotlinx.coroutines, Kotlin test, llama.cpp `/completion`.

## Global Constraints

- Keep prompt order deterministic: instruction, scenario, history, new action.
- Analysis and warmup keep the same session ID and slot.
- Accept only exact trimmed one-character digits `0`–`9`; threshold is `>= 5`.
- Never emit, persist, or log candidate/analysis/model text.
- Preserve the new, resumed, and following-turn warm-slot contracts.

---

### Task 1: Return candidate key and fragment from retrieval

**Files:** Modify `server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolver.kt`, `server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentIndexRegistry.kt`, and `server/src/test/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolverTest.kt`.

**Produces:** `suspend fun RuntimeFragmentResolver.resolve(...): RuntimeFragmentDefinition?`.

- [ ] **Step 1: Write the failing test**

```kotlin
assertEquals(RuntimeFragmentDefinition("lighthouse", "Mira avoids it."), resolved)
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :server:test --tests de.aidadventure.server.runtimefragments.RuntimeFragmentResolverTest --console=plain`

Expected: compilation fails because `resolve` returns `String?`.

- [ ] **Step 3: Implement minimal retrieval preservation**

```kotlin
// Store matchText and fragment fields; reconstruct the selected definition.
return RuntimeFragmentDefinition(matchText, fragment)
```

Update fakes/callers to use the definition.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :server:test --tests de.aidadventure.server.runtimefragments.RuntimeFragmentResolverTest --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Run: `git add server/src/main/kotlin/de/aidadventure/server/runtimefragments server/src/test/kotlin/de/aidadventure/server/runtimefragments && git commit -m "refactor: retain runtime fragment retrieval key"`

### Task 2: Define temporary relevance prompt and strict score parser

**Files:** Modify `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`, `server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt`, and `server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt`; create `server/src/main/kotlin/de/aidadventure/server/prefill/FragmentRelevance.kt` and `server/src/test/kotlin/de/aidadventure/server/prefill/FragmentRelevanceTest.kt`.

**Produces:** `FragmentRelevanceRequest`, `PromptFactory.buildRelevance`, and `internal fun parseFragmentRelevance(response: String): Int?`.

- [ ] **Step 1: Write failing parser/prompt tests**

```kotlin
assertEquals(5, parseFragmentRelevance("5"))
assertEquals(9, parseFragmentRelevance(" 9\\n"))
assertNull(parseFragmentRelevance("5."))
assertNull(parseFragmentRelevance("10"))
assertNull(parseFragmentRelevance("five"))
```

Assert the temporary candidate key is a system message and the fixed analysis prompt is the final user message.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :server:test --tests de.aidadventure.server.prefill.FragmentRelevanceTest --tests de.aidadventure.server.game.PromptFactoryTest --console=plain`

Expected: compilation fails because parser and relevance prompt are missing.

- [ ] **Step 3: Implement exact parsing and fixed prompt**

```kotlin
internal fun parseFragmentRelevance(response: String): Int? =
    response.trim().takeIf { it.length == 1 && it[0] in '0'..'9' }?.digitToInt()
```

Use the fixed prompt: `Rate how well the preceding key fits the current game situation. Reply with exactly one digit from 0 to 9; 0 means no fit and 9 means perfect fit.`

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :server:test --tests de.aidadventure.server.prefill.FragmentRelevanceTest --tests de.aidadventure.server.game.PromptFactoryTest --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Run: `git add server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt server/src/main/kotlin/de/aidadventure/server/prefill/FragmentRelevance.kt server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt server/src/test/kotlin/de/aidadventure/server/prefill/FragmentRelevanceTest.kt && git commit -m "feat: define fragment relevance prompt"`

### Task 3: Run scoring and canonical warmup on the retained slot

**Files:** Modify `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`, `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt`, `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt`, and `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`.

**Produces:** `PrefillGateway.scoreFragment(request, slot): String` and `PrefillCoordinator.rerankAndWarmup(request, candidate, requestId)`.

- [ ] **Step 1: Write failing gateway/coordinator tests**

```kotlin
assertEquals(1, completionBody.getValue("n_predict").jsonPrimitive.int)
assertEquals("7", gateway.scoreFragment(relevanceRequest(server.port), LlamaCppSlot(2)))
assertEquals(listOf("score", "warmup"), fake.slotLifecycle.map(SlotLifecycleEvent::operation))
assertFalse(warmup.history.any { it.content == "candidate key" || it.content == FRAGMENT_RELEVANCE_PROMPT })
```

Cover accepted `5`, rejected `4`, malformed response, and thrown score. Rejected/malformed paths warm the canonical history without a fragment, and every path retains one slot.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --console=plain`

Expected: compilation fails because scoring and re-ranking do not exist.

- [ ] **Step 3: Implement gateway and coordinator state**

```kotlin
val response = postCompletion(port, CompletionRequest(prompt, slot.id, predictedTokens = 1, stream = false))
return requireNotNull(response.content)
```

Add a coordinator `Reranking` operation that performs score then warmup on its lease. Construct a new `WarmupRequest` after parsing: append `ChatEntry("runtime-fragment", candidate.fragment)` only for scores 5–9. Queue later prefills until this work completes; report failures with request ID, operation, and class only.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Run: `git add server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt && git commit -m "feat: rerank fragments on retained slot"`

### Task 4: Start re-ranking only after the completed SSE turn

**Files:** Modify `server/src/main/kotlin/de/aidadventure/server/Application.kt` and `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`.

**Consumes:** the full candidate returned for `completedHistory`; `coordinator.rerankAndWarmup`.

- [ ] **Step 1: Write failing route tests**

```kotlin
assertContains(response.bodyAsText(), "event: done")
assertEquals(candidate, registry.coordinator.rerankCandidates.single())
assertEquals(completedHistory, registry.coordinator.rerankRequests.single().history)
assertTrue(registry.coordinator.warmupRequests.isEmpty())
```

Cover no candidate and resolver failure: both preserve `done` and start no background operation.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :server:test --tests de.aidadventure.server.ApplicationTest --console=plain`

Expected: compilation fails because the route directly warms the fragment.

- [ ] **Step 3: Replace direct warmup with re-ranking**

```kotlin
val candidate = runtimeFragmentResolver.resolve(completedHistory, resolved.request.runtimeFragments)
if (candidate != null) coordinator.rerankAndWarmup(
    WarmupRequest(..., history = completedHistory), candidate, call.callId ?: "unknown",
)
```

Keep this after stream collection, preserve cancellation, and log only safe failure fields under `post_turn_fragment_rerank`.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :server:test --tests de.aidadventure.server.ApplicationTest --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Run: `git add server/src/main/kotlin/de/aidadventure/server/Application.kt server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt && git commit -m "feat: rerank next runtime fragment"`

### Task 5: Prove cache contract and verify all modules

**Files:** Modify `server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt`, `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`, and this plan.

- [x] **Step 1: Write failing cache-chain tests**

```kotlin
assertCachePrefix(factory.buildWarmup(acceptedWarmup), factory.build(nextTurn))
assertWarmSlotLifecycle(fake, listOf("acquire", "turn", "score", "warmup", "prefill", "turn"))
```

Cover accepted/rejected candidates; canonical warmup never contains the key or analysis prompt. Keep current new-game and resumed-game checks unchanged.

- [x] **Step 2: Verify cache tests then final build**

Run: `./gradlew :server:test --tests de.aidadventure.server.game.PromptFactoryTest --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --console=plain && git diff --check && ./gradlew check --console=plain`

Expected: no diff errors and `BUILD SUCCESSFUL`.

- [x] **Step 3: Commit**

Run: `git add docs/superpowers/plans/2026-07-19-runtime-fragment-reranking.md server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt && git commit -m "test: verify fragment reranking cache contract"`
