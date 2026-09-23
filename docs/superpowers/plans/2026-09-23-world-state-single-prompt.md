# Single-prompt World State Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve location and narrative focus in one cache-preserving structured completion after each turn.

**Architecture:** Build one analysis message after the canonical turn prompt and actual game-master response. Generate one schema requiring narrative focus and, when a location catalog exists, a catalog-enumerated location. Parse both fields independently so invalid values retain the previous state. Keep the same retained slot and existing post-analysis warmup.

**Tech Stack:** Kotlin, kotlinx.serialization, Ktor/llama.cpp gateway, Gradle tests.

## Global Constraints

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- A prefill and its turn must use the same `sessionId`; only llama.cpp's returned `tokens_cached` and `tokens_evaluated` metrics prove cache reuse.
- Preserve cache-contract tests for new games, resumed games, and following turns.
- Treat every LLM response as untrusted text.

---

### Task 1: Combine World State analysis

**Files:**
- Modify `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt`
- Modify `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Test `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateResolverTest.kt`
- Test `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt`
- Test `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`

- [x] Change gateway test to expect one non-streaming structured completion with both fields and the retained slot/cache flag.
- [x] Run the focused gateway test and confirm it fails because the current implementation sends two completions.
- [x] Add combined-result parser tests, including independent fallback for invalid location and focus.
- [x] Implement one combined schema and one analysis completion; omit `location` from required fields when no catalog exists, and preserve catalog enum validation when it does.
- [x] Run focused World State and coordinator tests. `./gradlew check` was started but remained silent in `:server:test` for several minutes and was interrupted; the focused test set passed afterwards.
- [x] Review the diff and commit the completed change.
