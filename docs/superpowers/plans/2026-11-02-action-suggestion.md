# Action Suggestion Implementation Plan

> **For agentic workers:** Use subagent-driven development. Track steps with checkboxes.

**Goal:** Stream a cancellable continuation into the player composer after double Tab, preserving the session cache and history.

**Architecture:** Admit suggestions exclusively through the session coordinator after all background work; stream via a dedicated endpoint and restore normal prefill asynchronously for the latest visible action. The browser owns text and cancellation, and ignores stale events.

**Tech Stack:** Kotlin, Ktor, Kotlin/JS React, llama.cpp SSE.

**Spec:** `docs/superpowers/specs/2026-11-02-action-suggestion-design.md`

## Global Constraints

Preserve deterministic normal turn prompts, session ID and retained slot; keep history unchanged. Do not weaken existing cache-contract tests. Final verification is `./gradlew check`.

## Review Focus

- Esc while waiting must not cancel background work or start generation.
- Esc during streaming must stop the actual model request and unlock immediately.
- Edits or session switches must invalidate late events and stale prefills.
- Repeated completion must not insert temporary prompts into history.
- Empty and partially typed actions must both yield correct normal turn prompts and cache metrics.

### Task 1: Server admission, generation and cache

**Files:** `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt`, `server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt`, `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`, `server/src/main/kotlin/de/aidadventure/server/Application.kt`, relevant API models and validation; tests in `PrefillCoordinatorTest.kt`, `PromptFactoryTest.kt`, `LlamaCppGatewayTest.kt`, and `ApplicationTest.kt`.

- [ ] Add failing deterministic coordinator tests for waiting on all background stages, cancellation while waiting and generating, retained exclusive slot, and subsequent latest-action prefill.
- [ ] Add failing prompt and gateway tests covering unchanged history, typed prefix, repeated suggestions, exact subsequent turn prefix and cache metrics; retain existing new-game, resume and following-turn contracts.
- [ ] Add validated suggestion request and SSE endpoint, with distinct waiting, generation, token and terminal events. Propagate client cancellation through the gateway to the model request.
- [ ] Implement coordinator admission without interrupting background work; generate on the session lease and restore normal prefill from the latest action, respecting revisions and turns.
- [ ] Run the targeted server tests; fix failures and commit the server changes.

### Task 2: Composer and stream client

**Files:** `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`, `PrefillSubmission.kt`, `api/AdventureApi.kt`, supporting stream code; tests in `AdventureAppNavigationTest.kt`, `PrefillSubmissionTest.kt`, and API stream tests.

- [ ] Add failing browser tests for double Tab with focus, incremental continuation, waiting/generating status, read-only editing, Esc in both phases, failure, session change, late events and repeat completion; preserve native navigation elsewhere.
- [ ] Implement an independently abortable suggestion stream and integrate it with the composer and revision-aware prefill scheduling. Preserve displayed text on cancellation and release editing immediately.
- [ ] Run the relevant browser tests and commit the client changes.

### Task 3: Integration and verification

- [ ] Review the combined diff against the spec and failure cases, including prompt/slot invariants and user changes in the primary checkout.
- [ ] Run `./gradlew check` without exclusions and resolve failures; record actual cache evidence and limitations.
- [ ] Merge the task branch into main without disturbing existing changes, remove its worktree and branch, and confirm status and stopped processes.
