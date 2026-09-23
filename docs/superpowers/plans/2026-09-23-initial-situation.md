# Initial Situation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a game's YAML frontmatter define the initial location and a one-time setting used only for generating the opening text.

**Architecture:** Parse `initial-situation.world-state.location` into the new session's authoritative `WorldState` and expose `initial-situation.setting` separately. Send the setting on the introduction turn request only, where `PromptFactory` places it before the deterministic final player action. Update the example game to demonstrate both fields.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, js-yaml, Gradle.

## Global Constraints

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Prompt caching is release-blocking; preserve the cache-contract tests for new games, resumed games, and following turns.
- Treat every LLM response as untrusted text.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.

---

### Task 1: Parse and pass the one-time initial situation

**Files:**
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt`
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt`
- Modify `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`
- Modify `server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt`
- Modify `web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt`
- Modify `web/src/jsTest/kotlin/de/aidadventure/web/TurnSubmissionTest.kt`
- Modify `server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt`
- Modify `examples/test-game.md`

- [ ] Extend parsed scenario metadata with optional `initialSituationSetting` and read/validate the nested `world-state.location` and nonblank `setting` fields under `initial-situation`; carry the nested location into the session's `initialWorldState`.
- [ ] Add a default-empty serialized `initialSituation` field to `TurnRequest`; have `TurnRequestMapper` set it only for `submitIntroduction`, leaving prefill, warmup, and later turns empty.
- [ ] Add the setting as a distinct user context immediately before `playerAction` in `PromptFactory.build`, so it appears only in the introduction request and never in stable system context or history.
- [ ] Add focused tests proving parsing and location initialization, introduction-only request mapping, and omission from ordinary and warmup prompts.
- [ ] Update `examples/test-game.md` frontmatter to show `initial-situation.world-state.location` and `initial-situation.setting`.
- [ ] Run `./gradlew check` and `git diff --check`; retain all prompt-cache contract tests unchanged.
