# Server Fragment Prefill Order Implementation Plan

> **For agentic workers:** Execute task by task with tests.

**Goal:** Warm a resolved next runtime fragment on the retained server slot before later player-input prefills run.

**Architecture:** Keep browser debounce and DTOs unchanged. The `/api/turn` route accumulates tokens, resolves a next fragment from completed history, and starts the existing coordinator warmup. While warming, the coordinator queues a new input prefill on the retained slot.

**Tech Stack:** Kotlin/JVM, Ktor SSE, kotlinx.coroutines Flow, llama.cpp gateway, Kotlin test.

## Constraints

- Preserve same session ID, retained slot, deterministic prompt order, and hidden fragments.
- Start post-turn warmup only for a nonblank next fragment.
- Log failures using request ID, operation, and exception type only.
- Keep no-fragment turns and browser behavior unchanged.

### Task 1: Capture the existing coordinator queue contract

**Files:** Modify `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`.

- [x] Write `post turn fragment warmup queues later input prefill on its retained slot`: call `warmup` with history ending `runtime-fragment`; while the controllable gateway is warming, submit revision 1; assert `QUEUED`; complete warmup; await prefill; assert equal warmup and prefill slots.
- [x] Run `./gradlew --no-daemon :server:test --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --console=plain`; expected PASS because this is established coordinator behavior.

### Task 2: Start post-turn fragment warmup from the turn route

**Files:** Modify `server/src/main/kotlin/de/aidadventure/server/Application.kt`; test `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`.

- [x] Add failing route tests with a resolver fake returning a current fragment for the initial turn resolution and a next fragment for completed history. Consume a turn with token `The door opens.` and assert exactly one recorded coordinator warmup. Its history must be current runtime fragment, player action, generated game-master text, then next runtime fragment. Add null and throw-on-second-resolution cases; both stream done and record no warmup.
- [x] Run `./gradlew --no-daemon :server:test --tests de.aidadventure.server.ApplicationTest --console=plain`; expected FAIL because the route does not yet accumulate completed history or warm it.
- [x] In the turn route, append each streamed token to a local `StringBuilder` before emitting it. After the flow completes, build `resolved.request.history + player action + generated game-master text`; resolve the next fragment; when nonblank call `coordinator.warmup` with a `WarmupRequest` containing that history plus `ChatEntry("runtime-fragment", nextFragment)`, same session/model/port/scenario/runtime definitions, and the request ID. Catch resolver/warmup exceptions separately, rethrow cancellation, and log only safe fields. Do not alter the SSE done event.
- [x] Run route and coordinator tests together; expected PASS.

### Task 3: Verify and commit

- [x] Run `./gradlew check --console=plain`; expected `BUILD SUCCESSFUL`.
- [ ] Check completed plan items and commit the server code, tests, revised spec, and this plan as `fix(cache): warm next fragment after turn`.
