# Place WorldState Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace generic world-state proposals with a browser-persisted, validated current-place value.

**Architecture:** Server and web share `WorldState(place: String = "")`. The Secondary resolver first classifies scene movement, then extracts a destination only for a move. Kotlin validates both terminal results and accepts a destination only if it occurs in the completed game-master response.

**Tech Stack:** Kotlin, kotlinx.serialization, Ktor, llama.cpp `/completion`, Kotlin/JS.

## Global Constraints

- LLM output is untrusted; invalid, missing, ambiguous, or non-verbatim destinations keep the previous state.
- Secondary work remains post-stream and must not change the deterministic Primary prompt order.
- The browser persists world state and the server remains stateless.

---

### Task 1: Replace the shared state model

**Files:** server `worldstate/WorldState.kt`; web `model/Models.kt`; their tests and scenario-frontmatter tests.

- [ ] Write failing serialization and frontmatter tests for `WorldState(place = "Küche")` and missing state as `WorldState()`.
- [ ] Replace revision, entities, facts, proposals, and reducer with `@Serializable data class WorldState(val place: String = "")` and a snapshot containing only a nonblank current place.
- [ ] Change frontmatter to accept only `world-state.place` as a nonblank string.
- [ ] Run focused server and web tests; commit.

### Task 2: Add validated two-step place resolution

**Files:** server `worldstate/WorldStateResolver.kt`; `WorldStateResolverTest.kt`.

- [ ] Write failing tests for `stay`, `move` plus a verbatim target, malformed/multiple finals, and a target absent from the response.
- [ ] Make the resolver return `WorldState?`: parse only the last terminal movement final; for a move issue the target prompt; accept only one bounded target that is a case-insensitive whole-word occurrence in the game-master response.
- [ ] Use a fixed few-shot movement prompt and target prompt; do not include the previous place; preserve the 300-second timeout and request-ID-only failure logs.
- [ ] Run resolver tests; commit.

### Task 3: Wire state acceptance and UI/API migration

**Files:** `Application.kt`, `PromptFactory.kt`, API and web tests, popover tests.

- [ ] Write failing route and prompt tests that prove accepted places are returned in `done`, invalid candidates preserve the old place, and the Primary snapshot states only the canonical place.
- [ ] Replace proposal/reducer wiring with the resolver result and migrate all web/API fixtures to `place`.
- [ ] Run focused JVM and Kotlin/JS tests, then `./gradlew check`; commit.
