# Location Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist locations only from a canonical YAML scenario catalog.

**Architecture:** Kotlin/JS parses `locations` next to the initial `world-state`, forwards the catalog with requests, and the server constrains its retained-slot structured location completion to those names. Kotlin accepts only a catalogued name explicitly present in narration.

**Tech Stack:** Kotlin/JS, Kotlin/JVM, kotlinx.serialization, Ktor, llama.cpp JSON schema, Gradle.

## Global Constraints

- Preserve deterministic Primary prompt construction and the cache-contract tests.
- Treat LLM output as untrusted and retain the prior state on invalid or unconfirmed output.
- Browser IndexedDB owns scenario and session data; the server stays stateless.
- Final verification is `./gradlew check`.

---

### Task 1: Parse and transport locations

**Files:**

- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`
- Test: web request-mapper tests

**Interfaces:** `ParsedScenario.locations: List<String>`; matching serializable `locations: List<String> = emptyList()` in web and server turn/prefill requests.

- [ ] Write failing tests for parsing `locations: [Kai, Leuchtturm]`, rejecting duplicates and rejecting `world-state.location: Kai` when `Kai` is absent.
- [ ] Run `./gradlew :web:jsTest --tests '*ScenarioFrontmatterTest*'`; verify failure.
- [ ] Add strict string-list parsing, membership validation, and mapper/wire fields.
- [ ] Re-run the focused web tests; verify pass.
- [ ] Commit with `git add web/src server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt && git commit -m "feat(state): forward scenario locations"`.

### Task 2: Resolve only catalogued locations

**Files:**

- Modify: `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateResolverTest.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`

**Interfaces:** `WorldStateResolutionRequest.locations: List<String>` and `WorldStateAnalysis.currentLocationSchema(locations, previousLocation)`.

- [ ] Write failing tests that accept `Leuchtturm` after `You enter Leuchtturm.`, reject a non-catalogued name, and retain `Kai` when narration only says `Leuchtturm remains locked.`.
- [ ] Run `./gradlew :server:test --tests '*WorldStateResolverTest*'`; verify failure.
- [ ] Replace the free movement/location chain with one structured current-location request. Build its enum from the unique catalog plus the prior location; preserve focus resolution, retained slot, cache prompt, and warmup order.
- [ ] Assert in the gateway test that the completion uses the retained slot, `cache_prompt=true`, and a schema enum containing `Kai` and `Leuchtturm`.
- [ ] Run `./gradlew :server:test --tests '*WorldStateResolverTest*' --tests '*LlamaCppGatewayTest*' --tests '*ApplicationTest*'`; verify pass.
- [ ] Commit with `git add server/src/main server/src/test && git commit -m "fix(state): constrain locations to scenario catalog"`.

### Task 3: Update the lighthouse example and verify

**Files:**

- Modify: `examples/test-game.md`

- [ ] Add frontmatter with initial `Kai` and catalog entries `Kai`, `Leuchtturm`, `Hafenkneipe`, `Schuppen der Hafenmeisterin`, and `Steg`.
- [ ] Run `git diff --check` and `./gradlew check`; verify no whitespace errors and all checks pass.
- [ ] Commit with `git add examples/test-game.md && git commit -m "docs(example): declare lighthouse locations"`.
