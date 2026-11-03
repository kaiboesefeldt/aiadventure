# Narrative Focus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `unresolvedSituation` with a retrospectively derived `narrativeFocus` that can represent exploration as well as an active plot thread.

**Architecture:** Rename the shared WorldState property and every wire/schema/snapshot reference to `narrativeFocus`. Browser persistence migrates both legacy `activeThread` and the current `unresolvedSituation` into the new property. The sequential resolver keeps its location pass, then derives one short focus phrase from the prior focus, the player action, and the completed GM response.

**Tech Stack:** Kotlin/JVM, Kotlin/JS, kotlinx.serialization, Ktor, local llama.cpp, kotlin.test.

## Global Constraints

- Primary prompt order and accepted-world-state hand-off remain deterministic and retain cache-prefix coverage.
- The Secondary model receives one bounded structured task at a time; its response remains untrusted.
- Schema `maxLength: 160` guides generation; Kotlin preserves valid complete outputs beyond that recommendation.
- Browser IndexedDB owns session state and must read `activeThread` and `unresolvedSituation` from existing sessions.
- TRACE logs retain complete local Secondary outputs.

---

### Task 1: Rename state and migrate persisted browser sessions

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldState.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateTest.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/WorldStatePopoverTest.kt`

**Interfaces:**
- Produces `WorldState(location: String = "", narrativeFocus: String = "")` on server and browser endpoints.
- Decodes either old property into `narrativeFocus`, preferring an already-present `narrativeFocus`.
- Renders `NARRATIVE FOCUS: <focus>` only for nonblank focus.

- [ ] **Step 1: Write failing model, snapshot, JSON, and migration tests.**

```kotlin
assertEquals("AUTHORITATIVE CURRENT LOCATION: Kitchen\nNARRATIVE FOCUS: The rusty mechanism", WorldStateSnapshot.render(WorldState("Kitchen", "The rusty mechanism")))
assertEquals("{\"narrativeFocus\":\"The rusty mechanism\"}", worldStateJson(WorldState(narrativeFocus = "The rusty mechanism")))
assertEquals(WorldState(narrativeFocus = "The lighthouse"), decodePersistedGameSession("""{"id":"s","scenarioId":"x","title":"x","messages":[],"createdAt":1,"updatedAt":1,"worldState":{"unresolvedSituation":"The lighthouse"}}""").worldState)
assertEquals(WorldState(narrativeFocus = "The lighthouse"), decodePersistedGameSession("""{"id":"s","scenarioId":"x","title":"x","messages":[],"createdAt":1,"updatedAt":1,"worldState":{"activeThread":"The lighthouse"}}""").worldState)
```

- [ ] **Step 2: Run focused tests and verify they fail.**

Run: `./gradlew :server:test --tests de.aidadventure.server.worldstate.WorldStateTest :web:browserTest --tests de.aidadventure.web.WorldStatePopoverTest`

Expected: compilation or assertions fail because `narrativeFocus` does not exist.

- [ ] **Step 3: Rename the two data models and implement the dual legacy migration.**

```kotlin
@Serializable data class WorldState(val location: String = "", val narrativeFocus: String = "")

val legacyFocus = worldState["unresolvedSituation"] ?: worldState["activeThread"]
```

Remove both legacy keys from the migrated JSON object and insert `narrativeFocus` only when the current key is absent.

- [ ] **Step 4: Re-run focused tests and commit.**

Run: `./gradlew :server:test --tests de.aidadventure.server.worldstate.WorldStateTest :web:browserTest --tests de.aidadventure.web.WorldStatePopoverTest`

Commit: `git commit -m "feat(state): rename narrative focus"`

### Task 2: Retrospectively resolve the narrative focus

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateResolverTest.kt`

**Interfaces:**
- The final structured response is `{"narrativeFocus":"..."}`.
- Resolver input labels are `PRIOR NARRATIVE FOCUS`, `PLAYER ACTION`, and `GAME-MASTER RESPONSE`.
- The Primary snapshot never receives the old situation label.

- [ ] **Step 1: Write failing resolver-schema and prompt tests.**

```kotlin
val reply = "{\"narrativeFocus\":\"The rusty mechanism in the lighthouse\"}"
assertEquals(WorldState("Market Square", "The rusty mechanism in the lighthouse"), resolver.resolve(request(reply)))
assertTrue(prompt.contains("Retrospectively update the prior focus from the action and response."))
assertTrue(schema["required"]!!.toString().contains("narrativeFocus"))
```

- [ ] **Step 2: Run the resolver test and verify it fails.**

Run: `./gradlew :server:test --tests de.aidadventure.server.worldstate.WorldStateResolverTest`

Expected: old result type, property name, schema, and prompt fail the new expectations.

- [ ] **Step 3: Replace only the focus-specific resolver symbols and prompt.**

```kotlin
const val FOCUS_PROMPT = "Retrospectively update the prior focus from the action and response. Return one short phrase naming the single story element now most central. Return \"\" only if no narrative focus exists."
```

Rename `UnresolvedSituationResult`, `situationSchema`, parser function, resolution variable, and trace phase to their `NarrativeFocus` equivalents. Preserve the independent location fallback and complete-output TRACE logging.

- [ ] **Step 4: Re-run focused tests and commit.**

Run: `./gradlew :server:test --tests de.aidadventure.server.worldstate.WorldStateResolverTest`

Commit: `git commit -m "feat(state): resolve narrative focus"`

### Task 3: Propagate the renamed wire contract and verify cache hand-off

**Files:**
- Modify: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/model/ApiModelsTest.kt`
- Modify: `docs/superpowers/specs/2026-07-30-active-story-thread-design.md`
- Modify: `docs/superpowers/plans/2026-07-30-active-story-thread.md`

**Interfaces:**
- SSE `done.worldState` and the post-turn `WarmupRequest.worldState` use identical `narrativeFocus` values.

- [ ] **Step 1: Write failing serialized SSE and validation expectations.**

```kotlin
assertContains(donePayload, "\"narrativeFocus\":\"The rusty mechanism\"")
assertEquals(acceptedWorldState, registry.coordinator.rerankRequests.single().worldState)
```

- [ ] **Step 2: Run focused API tests and verify they fail.**

Run: `./gradlew :server:test --tests de.aidadventure.server.ApplicationTest --tests de.aidadventure.server.model.ApiModelsTest`

- [ ] **Step 3: Update test fixtures and documentation; do not alter the established accepted-state hand-off.**

Keep `acceptedWorldState` as the sole value used for SSE `done` and canonical warmup. Update the prior plan to identify it as superseded by this plan.

- [ ] **Step 4: Run full verification and commit.**

Run: `./gradlew check`

Commit: `git commit -m "feat(state): propagate narrative focus"`
