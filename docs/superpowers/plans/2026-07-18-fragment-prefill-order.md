# Fragment Prefill Order Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Warm a selected runtime fragment immediately after its completed turn, before submitting any later player-action prefill.

**Architecture:** Reuse `/api/warmup` and the retained per-session llama.cpp slot. `PrefillSubmission` gains a fragment-warmup gate: it warms a successfully completed session containing a fragment, retains the newest debounced player prefill during that warmup, and releases it after success or failure. `PlayView` starts the gate only after a successful turn.

**Tech Stack:** Kotlin/JS, kotlinx.coroutines, React, existing `AdventureApi` warmup/prefill endpoints, Kotlin test.

## Global Constraints

- Use the completed session's existing session ID and server-owned retained llama.cpp slot.
- Warmup history contains the hidden `runtime-fragment` entry and precedes later player-action prefill.
- No fragment retains the existing delayed-prefill path.
- Warmup failure releases the newest queued player action silently.
- Preserve revision coalescing and ordinary transcript hiding.

---

## File Structure

- `web/src/jsMain/kotlin/de/aidadventure/web/PrefillSubmission.kt`: debounce, turn state, fragment-warmup gate, queued-prefill release.
- `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`: starts the gate after a successful completed turn.
- `web/src/jsTest/kotlin/de/aidadventure/web/PrefillSubmissionTest.kt`: cache ordering, no-fragment, and failure regression tests.
- `web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt`: PlayView lifecycle regression test.

### Task 1: Gate player prefill behind completed-fragment warmup

**Files:**

- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/PrefillSubmission.kt`
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/PrefillSubmissionTest.kt`

**Interfaces:**

- Consumes: `AdventureApi.warmup`, `AdventureApi.prefill`, `WarmupRequestMapper.map`, `PrefillRequestMapper.map`.
- Produces: `PrefillSubmission.turnFinished(session: GameSession, scenario: Scenario)` which starts background warmup only for a just-completed fragment-bearing session and then releases the newest held request.

- [ ] **Step 1: Write failing tests**

Add a `ControllableWarmupAdventureApi` based on `RecordingAdventureApi`; `warmup` records the request and waits on a `CompletableDeferred<Unit>`, while `completeWarmup()` completes it and `failWarmup()` completes it exceptionally. Add these tests:

```kotlin
@Test fun `fragment warmup completes before delayed player prefill`() = GlobalScope.promise<Unit> {
    val scheduler = ManualPrefillScheduler(); val api = ControllableWarmupAdventureApi()
    val prefill = PrefillSubmission(api, scheduler); val completed = completedSessionWithFragment()
    prefill.turnStarted(); prefill.turnFinished(completed, scenario())
    prefill.changed(completed, scenario(), "live-1", "open the door"); scheduler.runAll(); nextTick()
    assertEquals(listOf(completed.id), api.warmedSessionIds); assertTrue(api.prefilledActions.isEmpty())
    api.completeWarmup(); nextTick()
    assertEquals(listOf("open the door"), api.prefilledActions)
}

@Test fun `no fragment keeps existing delayed player prefill`() = GlobalScope.promise<Unit> {
    val scheduler = ManualPrefillScheduler(); val api = RecordingAdventureApi(); val prefill = PrefillSubmission(api, scheduler)
    prefill.turnStarted(); prefill.turnFinished(session())
    prefill.changed(session(), scenario(), "live-1", "open the door"); scheduler.runAll(); nextTick()
    assertTrue(api.warmupRequests.isEmpty()); assertEquals(listOf("open the door"), api.prefilledActions)
}

@Test fun `failed fragment warmup releases newest queued player prefill`() = GlobalScope.promise<Unit> {
    val scheduler = ManualPrefillScheduler(); val api = ControllableWarmupAdventureApi(); val prefill = PrefillSubmission(api, scheduler)
    val completed = completedSessionWithFragment(); prefill.turnStarted(); prefill.turnFinished(completed, scenario())
    prefill.changed(completed, scenario(), "live-1", "look"); prefill.changed(completed, scenario(), "live-1", "open the door")
    scheduler.runAll(); api.failWarmup(); nextTick()
    assertEquals(listOf("open the door"), api.prefilledActions); assertEquals(listOf(2L), api.prefillRevisions)
}
```

`completedSessionWithFragment()` must append the exact `runtime-fragment`, `player`, `game-master` sequence to `session()`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.PrefillSubmissionTest --console=plain`

Expected: FAIL because the required `turnFinished(session, scenario)` overload and gate do not exist.

- [ ] **Step 3: Implement the gate**

Add `fragmentWarmupActive`, `queuedAfterFragment`, and a value type:

```kotlin
private data class QueuedPrefill(val session: GameSession, val scenario: Scenario, val sessionId: String, val revision: Long, val action: String)
private var fragmentWarmupActive = false
private var queuedAfterFragment: QueuedPrefill? = null
```

Change the scheduled `changed` callback to construct `QueuedPrefill`. It must submit it immediately only when `!fragmentWarmupActive`; otherwise replace `queuedAfterFragment`. Start API prefill in `scope.launch` with the current `runCatching` behavior. Add:

```kotlin
fun turnFinished(session: GameSession? = null, scenario: Scenario? = null) {
    turnActive = false
    if (session == null || scenario == null || !session.endsWithRuntimeFragmentTurn()) return
    fragmentWarmupActive = true
    scope.launch {
        runCatching { api.warmup(WarmupRequestMapper.map(session, scenario)) }
        fragmentWarmupActive = false
        queuedAfterFragment?.also { queuedAfterFragment = null; submitIfCurrent(it) }
    }
}
```

Implement `endsWithRuntimeFragmentTurn()` as an internal/private helper requiring the final roles `runtime-fragment`, `player`, `game-master`, and make `submitIfCurrent` reject `turnActive` or a revision different from `nextRevision`. `turnStarted` must cancel the scheduler and discard any held request but not cancel an already-issued API request.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.PrefillSubmissionTest --console=plain`

Expected: PASS, including current debounce, cancellation, resume-warmup, and next-action tests.

- [ ] **Step 5: Commit task 1**

Run: `git add web/src/jsMain/kotlin/de/aidadventure/web/PrefillSubmission.kt web/src/jsTest/kotlin/de/aidadventure/web/PrefillSubmissionTest.kt && git commit -m "fix(web): warm fragments before action prefill"`

### Task 2: Start fragment warmup only after successful turns

**Files:**

- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt:192-195`
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt`

**Interfaces:**

- Consumes: `CompletedTurn.session` and Task 1's `turnFinished(session, scenario)`.
- Produces: successful completed fragment turns warm exactly once; failed turns call only parameterless lifecycle cleanup.

- [ ] **Step 1: Write a failing PlayView test**

Extend the existing navigation fake so `play` emits `"Mira warns the player"` then `"The keeper points east."`. Submit `"ask about lighthouse"`; after completion assert the post-turn warmup history ends exactly with:

```kotlin
listOf(
    ChatEntry("runtime-fragment", "Mira warns the player"),
    ChatEntry("player", "ask about lighthouse"),
    ChatEntry("game-master", "The keeper points east."),
)
```

Add a no-fragment fake and assert it adds no post-turn warmup request.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.AdventureAppNavigationTest --console=plain`

Expected: FAIL because `PlayView` currently invokes only `turnFinished()` from `finally`.

- [ ] **Step 3: Wire success before common cleanup**

Immediately after applying `completed` in `Main.kt`, add:

```kotlin
prefill.turnFinished(completed.session, props.scenario)
```

Leave the existing `finally` call parameterless so a failed turn only clears `turnActive`. The parameterless call must not clear `fragmentWarmupActive` begun in the successful branch.

- [ ] **Step 4: Run integration and cache-order tests**

Run: `./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.AdventureAppNavigationTest --tests de.aidadventure.web.PrefillSubmissionTest --console=plain`

Expected: PASS; fragment warmup happens once, no-fragment completion does not start one, and player input waits for its settlement.

- [ ] **Step 5: Commit task 2**

Run: `git add web/src/jsMain/kotlin/de/aidadventure/web/Main.kt web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt && git commit -m "fix(web): sequence fragment and input cache warmup"`

### Task 3: Verify and hand off

**Files:**

- Modify: `docs/superpowers/plans/2026-07-18-fragment-prefill-order.md`

- [ ] **Step 1: Run web verification**

Run: `./gradlew --no-daemon :web:compileTestKotlinJs :web:browserTest :web:test --console=plain`

Expected: PASS with zero failures.

- [ ] **Step 2: Run final repository verification**

Run: `./gradlew check --console=plain`

Expected: `BUILD SUCCESSFUL`; do not exclude dependency-install tasks.

- [ ] **Step 3: Mark plan completed and commit**

Run: `git add docs/superpowers/plans/2026-07-18-fragment-prefill-order.md && git commit -m "docs: record fragment prefill verification"`

## Plan Self-Review

- Spec coverage: Task 1 covers fragment warmup, queued action ordering, unchanged no-fragment behavior, failure release, and revisions; Task 2 limits the flow to successful completed turns; Task 3 verifies the Kotlin/JS and full-repository surfaces.
- Placeholder scan: no deferred requirements or unspecified test behavior remain.
- Type consistency: Task 1 defines `turnFinished(session: GameSession? = null, scenario: Scenario? = null)` and Task 2 invokes that exact signature.

