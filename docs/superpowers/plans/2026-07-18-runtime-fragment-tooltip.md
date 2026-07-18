# Runtime Fragment Tooltip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Let players click an info button on an eligible completed game-master message to inspect its associated runtime fragment.

**Architecture:** Derive associations from the persisted message order without changing IndexedDB models. A small transcript helper identifies the eligible game-master message. PlayView renders a local toggleable tooltip and closes it on outside click; partial streamed responses never qualify.

**Tech Stack:** Kotlin/JS, React, existing GameMessage model and browser tests.

## Global Constraints

- Eligible order is runtime-fragment, player, game-master.
- Show no control for first turns, unrelated messages, or partial streamed responses.
- The fragment remains absent from ordinary transcript rows; tooltip is its only visible presentation.
- Association works from persisted history after reload/resume without schema migration.
- Button click toggles tooltip; outside click closes it.

---

### Task 1: Derive fragment associations and render accessible tooltip controls

**Files:**

- Modify: web/src/jsMain/kotlin/de/aidadventure/web/Main.kt
- Modify: web/src/jsMain/resources/styles.css
- Modify: web/src/jsTest/kotlin/de/aidadventure/web/StreamedSessionTest.kt
- Modify: web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt

**Interfaces:**

- Produces internal fun runtimeFragmentForGameMaster(messages: List<GameMessage>, index: Int): String?.
- Produces an info button only when runtimeFragmentForGameMaster returns a value.

- [ ] **Step 1: Write failing association and rendering tests**

~~~
@Test fun associatesFragmentOnlyWithFollowingCompletedGameMaster() {
    val messages = listOf(
        GameMessage("runtime-fragment", "hidden context", 1),
        GameMessage("player", "look", 2),
        GameMessage("game-master", "The tower answers.", 3),
    )
    assertEquals("hidden context", runtimeFragmentForGameMaster(messages, 2))
    assertNull(runtimeFragmentForGameMaster(messages, 1))
}

@Test fun unrelatedAndPartialMessagesHaveNoFragmentControl() {
    assertNull(runtimeFragmentForGameMaster(listOf(GameMessage("game-master", "first", 1)), 0))
}
~~~

Add a component-level test that checks an eligible message has an Info control and does not render the fragment text until it is clicked.

- [ ] **Step 2: Run focused browser tests and verify failure**

Run: ./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.StreamedSessionTest --tests de.aidadventure.web.AdventureAppNavigationTest --console=plain

Expected: FAIL because the association helper and info control do not exist.

- [ ] **Step 3: Implement association, toggle, and outside-close behavior**

In PlayView, retain one open message index in React state. Iterate transcript messages with their original indices, filter hidden entries from ordinary rows, and for an eligible game-master row render a small button with aria-label Fragment information. On click toggle that row’s index. Render its fragment as role tooltip adjacent to the button only while open. Register a document click listener that clears the open index unless the event target is inside the row’s tooltip container; remove it in the effect cleanup. Keep streamed messages ineligible by requiring the complete persisted three-message sequence.

~~~
internal fun runtimeFragmentForGameMaster(messages: List<GameMessage>, index: Int): String? =
    messages.getOrNull(index)?.takeIf { it.role == "game-master" }
        ?.let { messages.getOrNull(index - 2)?.takeIf { it.role == "runtime-fragment" }?.content
            ?.takeIf { messages.getOrNull(index - 1)?.role == "player" } }
~~~

- [ ] **Step 4: Add conservative tooltip styles and run tests**

Add a compact high-contrast tooltip surface, focus-visible button outline, and no transcript-layout overflow. Run:

./gradlew --no-daemon :web:compileTestKotlinJs :web:browserTest :web:test --console=plain

Expected: PASS; fragment text is visible only after click and no non-eligible message exposes a control.

- [ ] **Step 5: Commit**

~~~
git add web/src/jsMain/kotlin/de/aidadventure/web/Main.kt web/src/jsMain/resources/styles.css web/src/jsTest/kotlin/de/aidadventure/web/StreamedSessionTest.kt web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt
git commit -m "feat(web): show runtime fragment tooltip"
~~~

## Plan self-review

- Spec coverage: task covers association, persistence-derived eligibility, click toggle, outside close, streaming exclusion, normal-row hiding, accessibility, styling, and browser tests.
- Placeholder scan: exact files, helper, tests, commands, expected results, and UI behavior are specified.
- Type consistency: GameMessage already supplies role, content, and timestamp; no storage change is needed.

