# Scenario and Session Deletion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users immediately delete a saved session or a scenario together with all of its saved sessions from browser IndexedDB.

**Architecture:** Repository interfaces expose deletion operations backed by IndexedDB. Scenario deletion runs one read-write transaction across scenarios and sessions, queries the existing `scenarioId` index, and removes all matching sessions. React renders separate right-aligned deletion controls and reloads the affected list after success.

**Tech Stack:** Kotlin/JS, React wrappers, IndexedDB, kotlinx.coroutines, Kotlin test.

## Global Constraints

- Persist scenarios and sessions only in browser IndexedDB; make no server calls.
- Deleting a scenario removes all sessions with its `scenarioId`; deleting a session removes only that ID.
- Never show a confirmation dialog.
- Disable delete controls during their operation and display the current client-facing persistence error on failure.
- Do not change prompt construction, warmup, prefill, turns, coordinators, leases, or slots.

---

### Task 1: Add deletion repository contracts and persistence

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioImportTest.kt`

**Interfaces:**
- Produces: `ScenarioRepository.delete(id: String)` and `SessionRepository.delete(id: String)`.
- Produces: scenario deletion which removes sessions through the `scenarioId` index in one IndexedDB transaction.

- [ ] **Step 1: Write the failing test and interface fixtures**

Add the new method to the test repositories and define the desired selection contract:

```kotlin
@Test
fun `scenario deletion selects sessions with the matching scenario id`() {
    val sessions = listOf(
        GameSession("a", "harbor", "A", "model", emptyList(), 1, 1),
        GameSession("b", "forest", "B", "model", emptyList(), 1, 1),
    )
    assertEquals(listOf("a"), sessionIdsForScenarioDeletion("harbor", sessions))
}
```

- [ ] **Step 2: Verify the test is red**

Run: `./gradlew :web:browserTest --tests 'de.aidadventure.web.persistence.ScenarioImportTest'`

Expected: compilation fails because the deletion contracts/helper do not yet exist.

- [ ] **Step 3: Implement the minimal IndexedDB operations**

Extend both repository interfaces with `suspend fun delete(id: String)`. Add `RepositoryStore.delete(id)` with `objectStore.delete(id)` in a read-write transaction. In `AdventureDatabase`, implement scenario deletion with `database.transaction(arrayOf(SCENARIOS, SESSIONS), "readwrite")`: delete the scenario, obtain matching values from `objectStore(SESSIONS).index("scenarioId").getAll(id)`, delete every returned session ID, then await transaction completion through the existing error wrappers. Implement `sessions.delete` using the generic store deletion.

- [ ] **Step 4: Verify the test is green**

Run: `./gradlew :web:browserTest --tests 'de.aidadventure.web.persistence.ScenarioImportTest'`

Expected: exit 0.

- [ ] **Step 5: Commit the persistence change**

```bash
git add web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioImportTest.kt
git commit -m "feat: add browser-local deletion repositories"
```

### Task 2: Render and wire the deletion controls

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt`

**Interfaces:**
- Consumes: deletion methods from Task 1.
- Produces: library-level scenario deletion and detail-level single-session deletion with immediate list refresh.

- [ ] **Step 1: Write failing browser component tests**

Use recording repositories and assert visible scoped controls:

```kotlin
assertNotNull(container.buttonNamed("Delete scenario"))
container.clickButtonNamed("Delete scenario")
nextBrowserTick()
assertEquals(listOf("scenario"), deletedScenarioIds)
assertNull(container.buttonNamed("scenario"))
```

Seed two sessions in a detail-view test, click the selected row's `Delete session` button, and assert only the selected ID was recorded while the other session remains rendered.

- [ ] **Step 2: Verify the browser test is red**

Run: `./gradlew :web:browserTest --tests 'de.aidadventure.web.AdventureAppNavigationTest'`

Expected: test fails because delete controls and callbacks do not exist.

- [ ] **Step 3: Implement the minimal UI behavior**

In `AdventureApp`, call the selected repository deletion method from the shared coroutine scope, clear prior errors, and reload the affected list after success. In `libraryView`, render each scenario as a row with its existing title opener and a distinct `Delete scenario` button that does not call `onOpen`.

Extend `DetailViewProps` with an asynchronous delete callback. Render a distinct `Delete session` button for each saved session. Track a pending deletion ID, disable controls while pending, reload `loadedSessions` after success, and reuse `clientMessage` for failures.

- [ ] **Step 4: Verify the browser test is green**

Run: `./gradlew :web:browserTest --tests 'de.aidadventure.web.AdventureAppNavigationTest'`

Expected: exit 0 and both scopes are asserted.

- [ ] **Step 5: Commit the UI behavior**

```bash
git add web/src/jsMain/kotlin/de/aidadventure/web/Main.kt web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt
git commit -m "feat: delete scenarios and saved sessions"
```

### Task 3: Verify the full branch

**Files:**
- Modify: `docs/superpowers/plans/2026-07-17-scenario-session-delete.md` (check completed tasks only after evidence)

**Interfaces:**
- Consumes: the persistence and UI deletion flow.
- Produces: fresh branch verification evidence.

- [ ] **Step 1: Run full verification**

Run: `./gradlew check`

Expected: exit 0 with Kotlin/JVM, Kotlin/JS, and all tests passing.

- [ ] **Step 2: Inspect final state**

Run: `git diff main...HEAD --check && git status --short`

Expected: no whitespace errors and no uncommitted source changes.

- [ ] **Step 3: Commit verified plan markers if changed**

```bash
git add docs/superpowers/plans/2026-07-17-scenario-session-delete.md
git commit -m "docs: record deletion implementation verification"
```
