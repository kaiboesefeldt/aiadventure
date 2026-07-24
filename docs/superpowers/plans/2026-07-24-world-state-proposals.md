# World State Proposal Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Secondary LLM introduce new world entities without needing to create internal IDs or ordered reducer operations.

**Architecture:** Replace the resolver's technical `WorldStatePatch` response with a semantic proposal: named entities and named facts. The deterministic reducer canonicalizes entity names into IDs, resolves facts against existing and newly proposed entities, and atomically accepts or rejects the complete proposal. The UI continues to receive the resulting `WorldState` from the existing SSE `done` event.

**Tech Stack:** Kotlin, kotlinx.serialization JSON schema, Ktor, kotlin.test, local llama.cpp.

## Global Constraints

- Keep the state authoritative, revisioned, and conservatively validated.
- Treat the LLM proposal as untrusted text; reject malformed, ambiguous, stale, or impossible proposals atomically.
- Preserve the existing deterministic prompt-prefix and prompt-cache flow.
- Keep operational logs free of scenario, history, actions, and LLM text outside the existing TRACE diagnostics.
- Final verification is `./gradlew check --console=plain --no-daemon`.

---

### Task 1: Translate semantic proposals deterministically

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldState.kt`
- Test: `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateReducerTest.kt`

**Interfaces:**
- Produces `WorldStateProposal`, `WorldStateEntityProposal`, and `WorldStateFactProposal`.
- Produces `WorldStateReducer.applyProposal(state: WorldState, proposal: WorldStateProposal): WorldState?`.
- The proposal has `baseRevision`, `entities(name, kind, summary)`, and `facts(name, entityName, value)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `accepts a named entity and fact without an internal id`() {
    val result = WorldStateReducer.applyProposal(WorldState(), WorldStateProposal(
        baseRevision = 0,
        entities = listOf(WorldStateEntityProposal("Leuchtturm", "location", "Seit drei Nächten dunkel.")),
        facts = listOf(WorldStateFactProposal("Unruhe", "Leuchtturm", "true")),
    ))

    assertEquals(WorldEntity("location", "Leuchtturm", "Seit drei Nächten dunkel."), result?.entities?.get("leuchtturm"))
    assertEquals("true", result?.facts?.get("Unruhe:leuchtturm"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests 'de.aidadventure.server.worldstate.WorldStateReducerTest.accepts a named entity and fact without an internal id' --console=plain --no-daemon`

Expected: compilation failure because `WorldStateProposal` and `applyProposal` do not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
@Serializable data class WorldStateProposal(
    val baseRevision: Long,
    val entities: List<WorldStateEntityProposal> = emptyList(),
    val facts: List<WorldStateFactProposal> = emptyList(),
)
@Serializable data class WorldStateEntityProposal(val name: String, val kind: String, val summary: String)
@Serializable data class WorldStateFactProposal(val name: String, val entityName: String, val value: String)
```

Implement `applyProposal` by rejecting a wrong revision or more than eight total entries, staging valid new entities under `canonicalEntityId(name)`, resolving each fact's `entityName` against that staged map, and returning one incremented revision only if all entries are valid.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests 'de.aidadventure.server.worldstate.WorldStateReducerTest' --console=plain --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/de/aidadventure/server/worldstate/WorldState.kt server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateReducerTest.kt
git commit -m "feat(state): translate named world state proposals"
```

### Task 2: Request and apply semantic proposals

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Test: `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateResolverTest.kt`
- Test: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`

**Interfaces:**
- `WorldStateResolver.resolve` returns `WorldStateProposal?`.
- `Application` calls `WorldStateReducer.applyProposal` and emits the accepted state in `StreamDone`.

- [ ] **Step 1: Write failing resolver and route tests**

Change the resolver fixture response to a named proposal with `entities` and `facts`, then assert the decoded entity has name `Harbourmaster`. Add an application route test whose resolver returns a named `Leuchtturm` proposal and assert the SSE `done` payload contains `"leuchtturm"` and `"Unruhe:leuchtturm"`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :server:test --tests 'de.aidadventure.server.worldstate.WorldStateResolverTest' --tests 'de.aidadventure.server.ApplicationTest' --console=plain --no-daemon`

Expected: compilation or assertion failure because the resolver still returns `WorldStatePatch` and the route still applies patches.

- [ ] **Step 3: Write minimal implementation**

Change the resolver prompt/schema from `operations` to these fields:

```json
{"baseRevision":0,"entities":[{"name":"Leuchtturm","kind":"location","summary":"..."}],"facts":[{"name":"Unruhe","entityName":"Leuchtturm","value":"true"}]}
```

State that names are ordinary display names, facts may refer to an entity in the same proposal, and the server derives all IDs. Decode `WorldStateProposal`. In the route, call `WorldStateReducer.applyProposal(request.worldState, candidate)` and retain the existing trace acceptance log and fallback to the previous state.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :server:test --tests 'de.aidadventure.server.worldstate.WorldStateResolverTest' --tests 'de.aidadventure.server.ApplicationTest' --console=plain --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt server/src/main/kotlin/de/aidadventure/server/Application.kt server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateResolverTest.kt server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt
git commit -m "feat(state): resolve named world state proposals"
```

### Task 3: Final integration verification

**Files:**
- Modify: `docs/superpowers/plans/2026-07-24-world-state-proposals.md`

- [ ] **Step 1: Mark the executed plan steps complete**

Mark all completed checkboxes in this file after their associated command has passed.

- [ ] **Step 2: Run the full project gate**

Run: `./gradlew check --console=plain --no-daemon`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect the final diff**

Run: `git diff --check && git status --short`

Expected: no whitespace errors; only the implementation, tests, and this plan are modified or staged.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/2026-07-24-world-state-proposals.md
git commit -m "docs(state): record proposal translation plan"
```
