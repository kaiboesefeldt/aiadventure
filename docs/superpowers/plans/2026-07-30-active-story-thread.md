# Unresolved Story Situation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist and prompt one bounded, unresolved story situation extracted in a separate Secondary-LLM call after each visible turn.

**Architecture:** Add `unresolvedSituation` with a compatible empty default to the shared server and browser `WorldState` models. Migrate browser-persisted `activeThread` values on read. Extend the existing sequential resolver after its location work, and use the accepted combined state for both SSE `done` and the canonical warmup. Render the situation separately from the authoritative location.

**Tech Stack:** Kotlin/JVM, Kotlin/JS, kotlinx.serialization, Ktor, local llama.cpp, kotlin.test.

## Global Constraints

- The Primary prompt remains deterministic: instruction, scenario, history, accepted world-state snapshot, action.
- Warmup and following turn use the identical accepted world state and retain the existing cache-prefix tests.
- The Secondary model receives one bounded structured task at a time; its text is untrusted.
- The `maxLength: 160` schema guides new Secondary generations; Kotlin accepts a valid returned situation beyond that recommendation.
- Scenario frontmatter remains location-only; browser IndexedDB remains the state owner.

---

### Task 1: Extend the shared persisted state and Primary snapshot

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldState.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateTest.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/WorldStatePopoverTest.kt`

**Interfaces:**
- Produces `WorldState(location: String = "", unresolvedSituation: String = "")` on both wire endpoints.
- Produces independent optional `AUTHORITATIVE CURRENT LOCATION` and `UNRESOLVED STORY SITUATION` lines; a situation renders even if location is empty.

- [ ] **Step 1: Write failing JVM and browser tests.**

Add a snapshot expectation for `WorldState("Küche", "Finde den Saboteur")`, a cache-prefix case whose warmup and next turn share that state, and a browser JSON expectation containing `unresolvedSituation` plus a legacy `activeThread` read migration.

- [ ] **Step 2: Run the focused tests and verify the new expectations fail.**

Run: `./gradlew :server:test --tests de.aidadventure.server.worldstate.WorldStateTest --tests de.aidadventure.server.game.PromptFactoryTest :web:browserTest --tests de.aidadventure.web.WorldStatePopoverTest`

Expected: assertions fail because the property and snapshot line do not exist.

- [ ] **Step 3: Add the compatible field and rendering.**

Add `unresolvedSituation: String = ""` after `location` in both models. Render the location exactly as today, then append `UNRESOLVED STORY SITUATION: <situation>` only for a nonblank situation.

- [ ] **Step 4: Re-run the focused tests.**

Expected: all Task-1 tests pass and existing JSON keeps `location` first.

### Task 2: Resolve the handlungsfaden after location

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateResolverTest.kt`

**Interfaces:**
- `LlamaCppWorldStateResolver.resolve` returns a state whose location follows the existing rules and whose `unresolvedSituation` comes from a final situation call, including when the previous location is empty.
- The situation schema requires `unresolvedSituation` with `maxLength: 160`; malformed output retains `previousState.unresolvedSituation`, while Kotlin accepts a valid oversized result.

- [ ] **Step 1: Write failing resolver tests.**

Use a local completion server that returns the current movement/location replies followed by `{"unresolvedSituation":"Find the lighthouse saboteur"}`; assert the returned state has both fields. Add a response with 161 characters and assert it is retained. Inspect the final request schema for `maxLength` 160.

- [ ] **Step 2: Run `./gradlew :server:test --tests de.aidadventure.server.worldstate.WorldStateResolverTest` and verify it fails for the missing situation request.**

- [ ] **Step 3: Implement the final, separate structured call.**

First calculate a location intermediate state instead of returning early when the previous location is empty. Then call `complete` with the prior situation, player action, and GM response. Parse and trim `UnresolvedSituationResult(unresolvedSituation)`, then return the intermediate location state merged with the accepted situation regardless of length. Preserve the entire previous state on transport/schema failure; an explicit empty valid value clears the situation. Log the complete local Secondary response at TRACE.

- [ ] **Step 4: Re-run the focused resolver test.**

Expected: the resolver makes its existing location calls followed by exactly one situation call and all assertions pass.

### Task 3: Preserve the accepted combined state through completion and prefill

**Files:**
- Modify: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`
- Modify: `docs/superpowers/specs/2026-07-30-active-story-thread-design.md`

**Interfaces:**
- SSE `done.worldState` and the post-turn `WarmupRequest.worldState` are the same resolver result.

- [ ] **Step 1: Write a failing application test whose fake resolver returns `WorldState("Leuchtturm", "Finde den Saboteur")`.**

Assert that the SSE done payload contains both fields and the coordinator's warmup request has the exact same state.

- [ ] **Step 2: Run `./gradlew :server:test --tests de.aidadventure.server.ApplicationTest` and verify it fails only if the state is not propagated.**

- [ ] **Step 3: Keep the existing state hand-off; adjust only tests or code needed for the new serialized field.**

The existing `acceptedWorldState` must remain the sole value passed to both `StreamDone` and `WarmupRequest`.

- [ ] **Step 4: Run the focused server suite, then `./gradlew check`.**

Expected: server tests and the complete multi-module gate pass; report any unrelated browser dependency failure separately.

- [ ] **Step 5: Commit the implementation and the design/plan documents.**

Run: `git add server web docs/superpowers/specs/2026-07-30-active-story-thread-design.md docs/superpowers/plans/2026-07-30-active-story-thread.md && git commit -m "fix(state): clarify unresolved situation"`
