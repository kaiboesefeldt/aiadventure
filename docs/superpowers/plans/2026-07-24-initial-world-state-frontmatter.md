# Initial WorldState Frontmatter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Initialize every newly created browser game session from an optional, strictly validated `world-state` YAML frontmatter section.

**Architecture:** Extend the existing browser-only `ScenarioFrontmatter` parser so `ParsedScenario` carries an `initialWorldState`. Reuse the current serializable `WorldState` and `WorldEntity` models, always with revision zero. Extract session construction from the React callback into a pure internal factory so the client can parse the saved scenario at creation time and test the persisted initial value without changing the server or turn path.

**Tech Stack:** Kotlin/JS, React, `js-yaml`, kotlinx.serialization, Kotlin test, Gradle.

## Global Constraints

- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
- Existing sessions retain their persisted `worldState`; only creation of a new session reads the scenario frontmatter.
- `revision` is not author-configurable and every parsed initial state uses `0`.
- Facts are non-empty-key to arbitrary non-empty/empty string values; YAML values must nevertheless be text scalars.
- Reject malformed `world-state` at scenario import; do not silently discard it.
- Do not change prompt construction, prefill/warmup, turn flow, slots, or server APIs.

---

### Task 1: Parse and validate optional initial state

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt`

**Interfaces:**
- Consumes: YAML frontmatter and `de.aidadventure.web.model.WorldState`, `WorldEntity`.
- Produces: `ParsedScenario(promptMarkdown: String, runtimeFragments: List<RuntimeFragmentDefinition>, initialWorldState: WorldState)`.

- [ ] **Step 1: Write failing parser tests for a populated and omitted state.**

  Add model imports and these tests to `ScenarioFrontmatterTest`:

  ```kotlin
  @Test
  fun parsesInitialWorldStateWithRevisionZero() {
      val parsed = ScenarioFrontmatter.parse(
          """---
          world-state:
            entities:
              mira: { kind: npc, name: Mira, summary: Waiting at the quay }
            facts:
              mira-promised-a-meeting: "true"
          ---
          # Harbor
          """.trimIndent(),
      )

      assertEquals(0, parsed.initialWorldState.revision)
      assertEquals(WorldEntity("npc", "Mira", "Waiting at the quay"), parsed.initialWorldState.entities["mira"])
      assertEquals(mapOf("mira-promised-a-meeting" to "true"), parsed.initialWorldState.facts)
  }

  @Test
  fun omittedWorldStateUsesEmptyRevisionZeroState() {
      assertEquals(WorldState(), ScenarioFrontmatter.parse("# Harbor").initialWorldState)
  }
  ```

- [ ] **Step 2: Run the focused parser test class and confirm the new property does not exist.**

  Run: `./gradlew :web:jsTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest`

  Expected: compilation failure mentioning `initialWorldState`.

- [ ] **Step 3: Add the minimal parsed-state contract and strict YAML readers.**

  Import `WorldState` and `WorldEntity`; add the third `ParsedScenario` property with default `WorldState()` so existing parser callers remain source-compatible. In `parse`, load the frontmatter mapping once, pass it to both fragment and state readers, and return:

  ```kotlin
  ParsedScenario(
      promptMarkdown = strippedPrompt,
      runtimeFragments = readRuntimeFragments(rootEntries),
      initialWorldState = readInitialWorldState(rootEntries),
  )
  ```

  Implement `readInitialWorldState` with these exact checks:

  ```kotlin
  private fun readInitialWorldState(root: Array<Array<dynamic>>): WorldState {
      val value = root.firstOrNull { it[0] == "world-state" }?.get(1) ?: return WorldState()
      val entries = mappingEntries(value) ?: invalidFrontmatter()
      requireOnlyKeys(entries, setOf("entities", "facts"))
      return WorldState(
          entities = readEntities(entries.valueFor("entities")),
          facts = readFacts(entries.valueFor("facts")),
      )
  }
  ```

  `readEntities(null)` and `readFacts(null)` return empty maps. Otherwise each requires a YAML mapping. Entity IDs and fact keys must be non-blank strings. Each entity must be a mapping with exactly `kind`, `name`, `summary`, all present, text-typed, and non-blank. Each fact value must be a Kotlin/JS `String`; its value itself may be blank. `requireOnlyKeys` rejects unknown keys. Do not accept `revision` anywhere.

- [ ] **Step 4: Run the focused parser class and confirm the positive tests pass.**

  Run: `./gradlew :web:jsTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest`

  Expected: PASS.

- [ ] **Step 5: Add failing strict-validation tests.**

  Add one parameterized-style local list or individual tests that assert `assertFailsWith<IllegalArgumentException>` for each of: a `revision` key, an unknown `world-state` key, missing `summary`, blank entity ID, blank fact key, numeric fact value, and a list in place of `entities`. Also add a `ScenarioImport.fromFile(...)` assertion that one malformed state returns `ImportResult.Rejected("Invalid scenario frontmatter.")`.

- [ ] **Step 6: Run the focused test classes and confirm validation is initially incomplete.**

  Run: `./gradlew :web:jsTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --tests de.aidadventure.web.persistence.ScenarioImportTest`

  Expected: FAIL until all strict validation checks are implemented.

- [ ] **Step 7: Complete validation, preserving existing fragment behavior.**

  Add small private helpers for strict mapping keys, lookup by key, and required text values. Keep the existing `Invalid scenario frontmatter.` exception route unchanged, so `ScenarioImport` continues to convert every parser failure to its existing rejection message. Do not make root-level fields strict: `title` and current `runtime-fragments` must stay supported.

- [ ] **Step 8: Re-run parser and import tests.**

  Run: `./gradlew :web:jsTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --tests de.aidadventure.web.persistence.ScenarioImportTest`

  Expected: PASS.

- [ ] **Step 9: Commit the parser slice.**

  ```bash
  git add web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioImportTest.kt
  git commit -m "feat(state): parse initial world state"
  ```

### Task 2: Initialize and persist new sessions from the parsed state

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt`

**Interfaces:**
- Consumes: `Scenario`, `ScenarioFrontmatter.parse(scenario.markdown).initialWorldState` from Task 1.
- Produces: `internal fun newGameSession(scenario: Scenario, id: String, time: Long): GameSession` with `worldState` initialized from frontmatter.

- [ ] **Step 1: Write a failing pure session-construction test.**

  In `AdventureAppNavigationTest`, add imports for `WorldState` and `WorldEntity`, then add:

  ```kotlin
  @Test
  fun `new session persists the scenario initial world state`() {
      val scenario = Scenario(
          "harbor", "harbor.md", """---
          world-state:
            entities: { mira: { kind: npc, name: Mira, summary: Waiting } }
            facts: { tide: high }
          ---
          # Harbor
          """.trimIndent(), 1,
      )

      val session = newGameSession(scenario, "session-1", 2)

      assertEquals(WorldState(0, mapOf("mira" to WorldEntity("npc", "Mira", "Waiting")), mapOf("tide" to "high")), session.worldState)
  }
  ```

- [ ] **Step 2: Run the focused navigation test and confirm the factory is missing.**

  Run: `./gradlew :web:jsTest --tests de.aidadventure.web.AdventureAppNavigationTest`

  Expected: compilation failure mentioning `newGameSession`.

- [ ] **Step 3: Extract the session factory and use it from the Create callback.**

  Add this `internal` function near the other testable helpers at the end of `Main.kt`:

  ```kotlin
  internal fun newGameSession(scenario: Scenario, id: String, time: Long): GameSession = GameSession(
      id = id,
      scenarioId = scenario.id,
      title = "${scenario.name} adventure",
      messages = emptyList(),
      createdAt = time,
      updatedAt = time,
      worldState = ScenarioFrontmatter.parse(scenario.markdown).initialWorldState,
  )
  ```

  Replace the inline `GameSession(...)` in `onCreate` with the factory, retaining its current ID generation and repository `save(session)` call. The existing `GameSession` serializer writes `worldState`, so no IndexedDB migration or server change is needed.

- [ ] **Step 4: Run the focused navigation test and confirm it passes.**

  Run: `./gradlew :web:jsTest --tests de.aidadventure.web.AdventureAppNavigationTest`

  Expected: PASS.

- [ ] **Step 5: Add the existing-session regression test.**

  Add a test that constructs a `GameSession` with a non-empty `worldState`, supplies a scenario whose frontmatter has a different state, and asserts the existing session remains unchanged. This proves creation-time parsing does not overwrite resumed sessions.

- [ ] **Step 6: Run focused browser tests.**

  Run: `./gradlew :web:jsTest --tests de.aidadventure.web.AdventureAppNavigationTest --tests de.aidadventure.web.PrefillSubmissionTest`

  Expected: PASS.

- [ ] **Step 7: Commit the session-creation slice.**

  ```bash
  git add web/src/jsMain/kotlin/de/aidadventure/web/Main.kt web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt
  git commit -m "feat(state): initialize new sessions from frontmatter"
  ```

### Task 3: Run integration verification and record the completed design artifact

**Files:**
- Modify: `docs/superpowers/plans/2026-07-24-initial-world-state-frontmatter.md` only to mark completed checkboxes if the team tracks execution in the plan.
- Verify: `docs/superpowers/specs/2026-07-24-initial-world-state-frontmatter-design.md`

**Interfaces:**
- Consumes: parser and session-creation behavior from Tasks 1 and 2.
- Produces: a verified browser-only feature with unchanged server and cache behavior.

- [ ] **Step 1: Check formatting and the complete diff.**

  Run: `git diff --check` and `git diff --stat HEAD~2..HEAD`

  Expected: no whitespace errors; only the parser, session creation, tests, and committed design/plan artifacts are relevant.

- [ ] **Step 2: Run the full required build gate.**

  Run: `./gradlew check`

  Expected: PASS for both modules, including Kotlin/JS dependency tasks and browser tests.

- [ ] **Step 3: Verify the final repository state.**

  Run: `git status --short && git log -3 --oneline`

  Expected: no uncommitted implementation files; commits identify the parser and session creation slices.
