# Prompt Cache Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make prompt-cache compatibility and warm slot affinity non-negotiable, locally testable development contracts for new, resumed, and continuing games.

**Architecture:** `PromptFactoryTest` will assert that each later logical chat-message sequence has its cached predecessor as an exact prefix. `PrefillCoordinatorTest` will use its in-process `PrefillGateway` double to record acquisition, warmup/prefill/turn use, retention, and release, proving that a coordinator keeps one leased slot across each cache-reusing handoff. Neither suite starts or contacts llama.cpp.

**Tech Stack:** Kotlin/JVM, kotlin.test, coroutines, LangChain4j message values, existing `PrefillGateway` test double.

## Global Constraints

- Prompt caching is critical to the playable latency of this game; do not weaken it without replacing its proof.
- The cache-compatible prefix is instruction, original scenario, chronological history, then only an appended new action.
- A warmup, prefill, and turn for one coordinator must keep one lease and slot until normal TTL/close/failure cleanup.
- Tests must run without llama.cpp, HTTP, timing sleeps, or access to game data outside their local fixtures.
- Native `tokens_cached` and `tokens_evaluated` remain the runtime proof of actual backend cache reuse.

---

### Task 1: Lock prompt-chain prefix compatibility

**Files:**
- Modify: `server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt`

**Interfaces:**
- Consumes: `PromptFactory.buildWarmup(WarmupRequest)` and `PromptFactory.build(TurnRequest)`.
- Produces: three regression tests that compare exact `ChatMessage` sequences, not rendered/logged prompt text.

- [x] **Step 1: Write failing tests**

Add a private assertion that compares `expectedPrefix` against `actual.take(expectedPrefix.size)`, then add tests for an empty-history new game, a stored-history resumed game, and a completed player/assistant exchange followed by the next action.

- [x] **Step 2: Run the focused test task and verify the added assertions fail if the prefix contract is intentionally inverted**

Run: `./gradlew :server:test --tests 'de.aidadventure.server.game.PromptFactoryTest' --console=plain`

Expected: PASS after restoring the intended prefix assertions; a temporary inverted assertion must fail before it is restored.

- [x] **Step 3: Keep the production prompt factory minimal**

Do not add a runtime cache abstraction: the existing pure `PromptFactory` is the production boundary being protected.

- [x] **Step 4: Run the focused test task**

Run: `./gradlew :server:test --tests 'de.aidadventure.server.game.PromptFactoryTest' --console=plain`

Expected: PASS.

### Task 2: Lock leased-slot warmth and affinity

**Files:**
- Modify: `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`

**Interfaces:**
- Consumes: `SessionPrefillCoordinator`, `PrefillGateway`, and `ControllableGateway`.
- Produces: lifecycle assertions over explicit in-memory events: acquire, warmup/prefill/turn, retain, release.

- [x] **Step 1: Write failing lifecycle tests**

Add tests for (a) new-game warmup followed by its first turn, (b) resumed-history warmup followed by its first turn, and (c) completed turn followed by next prefill and turn. Each test asserts one acquired slot, the same `LlamaCppSlot` for every gateway call, no release before cleanup, and one release only during explicit close.

- [x] **Step 2: Run the focused test task and verify the lifecycle assertions fail when a retained slot is released before the next operation**

Run: `./gradlew :server:test --tests 'de.aidadventure.server.prefill.PrefillCoordinatorTest' --console=plain`

Expected: PASS after restoring the retained-slot behavior; a temporary early release must fail before it is restored.

- [x] **Step 3: Extend only the test double when needed**

Record slot lifecycle events and acquisition count inside `ControllableGateway`; do not add a llama.cpp dependency or production-only test hooks.

- [x] **Step 4: Run the focused test task**

Run: `./gradlew :server:test --tests 'de.aidadventure.server.prefill.PrefillCoordinatorTest' --console=plain`

Expected: PASS.

### Task 3: Persist the performance invariant

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/specs/2026-07-15-llama-cpp-prefill-cache-design.md`
- Modify: `docs/superpowers/specs/2026-07-16-resume-cache-warmup-design.md`
- Modify: `docs/superpowers/specs/2026-07-16-retain-turn-cache-design.md`
- Create: `docs/superpowers/specs/2026-07-17-prompt-cache-contract-design.md`

**Interfaces:**
- Consumes: the cache and coordinator contracts established by Tasks 1 and 2.
- Produces: repository instructions and specifications requiring local prefix/slot-contract tests for all three gameplay flows.

- [x] **Step 1: Document the invariant**

State that cache-compatible prefix and warm leased-slot affinity are release-blocking performance requirements. Name new game, resume, and following turn as required local tests, and distinguish them from native runtime cache metrics.

- [x] **Step 2: Run the complete verification task**

Run: `./gradlew check --console=plain`

Expected: PASS, including Kotlin/JS dependency tasks.

- [x] **Step 3: Review the working tree**

Run: `git diff --check && git status --short`

Expected: no whitespace errors; only the tests, instructions, specifications, and plan from this feature are changed.
