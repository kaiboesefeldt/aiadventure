# Retain Turn Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve a live session's llama.cpp slot after a game-master turn so the next prefill can append the player's new input to its KV cache.

**Architecture:** `SessionPrefillCoordinator` already models an idle retained lease as `Ready`. Successful turns will transition to that state rather than erasing the slot. Existing coordinator close and registry TTL cleanup continue to own release and erase behavior.

**Tech Stack:** Kotlin/JVM, kotlinx.coroutines, Kotlin test, Gradle.

## Global Constraints

- Keep prompt ordering deterministic: instruction, original scenario, history, new action.
- Keep a prefill and its turn on the same `sessionId`; only llama.cpp cache metrics prove reuse.
- Do not log scenario, history, player actions, or LLM output.
- Browser IndexedDB remains the owner of scenarios and sessions; the server remains stateless.

---

### Task 1: Retain the completed-turn lease

**Files:**

- Modify: `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt`

**Interfaces:**

- Consumes: `PrefillCoordinator.beginTurn(request): Flow<TurnStreamEvent>` and `PrefillCoordinator.submit(request): PrefillDisposition`.
- Produces: a `State.Ready(lease)` transition after a successful turn, allowing a later prefill to reuse `lease.slot`.

- [ ] **Step 1: Write the failing regression test**

  Add this test next to the other coordinator lifecycle tests:

  ```kotlin
  @Test
  fun `completed turn retains its slot for the next prefill`() = runBlocking {
      val fake = ControllableGateway()
      val coordinator = SessionPrefillCoordinator(fake)

      coordinator.beginTurn(turn("look")).toList()
      coordinator.submit(prefill(1, "open"))
      fake.awaitPrefill(1)

      assertEquals(fake.turnSlots.single(), fake.prefillSlots.single())
      assertTrue(fake.releasedSlots.isEmpty())
      coordinator.close()
  }
  ```

- [ ] **Step 2: Run the focused test and verify RED**

  Run:

  ```bash
  ./gradlew :server:test --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --console=plain
  ```

  Expected: the new test fails because `turnFinished` releases the turn lease,
  so the later prefill receives a newly acquired slot.

- [ ] **Step 3: Make the minimal state transition change**

  In `SessionPrefillCoordinator.turnFinished`, replace the successful
  `State.Turning` branch body with:

  ```kotlin
  is State.Turning -> if (current.execution === execution && current.lease === lease) {
      state = State.Ready(current.lease)
      null
  } else {
      null
  }
  ```

  Do not alter the `TurnWaiting` branch, release-on-failure behavior, or the
  existing `close` implementation.

- [ ] **Step 4: Run the focused test and verify GREEN**

  Run:

  ```bash
  ./gradlew :server:test --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --console=plain
  ```

  Expected: all `PrefillCoordinatorTest` tests pass, including the retained
  slot regression test.

- [ ] **Step 5: Run full project verification**

  Run:

  ```bash
  ./gradlew check --console=plain
  git diff --check
  ```

  Expected: both commands exit with status 0.

- [ ] **Step 6: Commit the implementation and plan**

  ```bash
  git add server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt \
      server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt \
      docs/superpowers/plans/2026-07-16-retain-turn-cache.md
  git commit -m "fix(cache): retain completed turn slots"
  ```
