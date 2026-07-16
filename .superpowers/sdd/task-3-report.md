# Task 3 Report: Per-session prefill coordinator and bounded registry

## Scope

Implemented the Task 3 coordinator, registry, and process-local slot ownership boundary:

- `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt`
- `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillRegistry.kt`
- `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillSlotLeaseManager.kt`
- `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppSlotTest.kt`
- `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`
- `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillRegistryTest.kt`

The pre-existing modification to `.superpowers/sdd/task-2-report.md` was left untouched.

## Behavior delivered

- One active prefill is retained per coordinator; only the newest pending prefill is retained while it runs.
- A turn discards pending prefill work, waits for the active prefill, and streams on that active work's leased slot.
- Revision checks reject stale prefill submissions.
- Generation checks prevent obsolete completion callbacks from changing current state.
- Closing, turn completion, stale callbacks, and prefill failure all use idempotent slot release.
- The registry returns one coordinator per `(sessionId, modelId, port)` key and closes/removes entries after a positive finite TTL.
- Registry lifecycle logs contain only a truncated SHA-256-derived opaque key; scenario, history, action, and model/session identifiers are not logged.

## Strict TDD record

### RED

Command:

```text
./gradlew :server:test --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --tests de.aidadventure.server.prefill.PrefillRegistryTest
```

Result: failed at test compilation because `SessionPrefillCoordinator`, `PrefillGateway`, `CoordinatorKey`, `PrefillCoordinator`, and `ExpiringPrefillRegistry` did not exist.

### GREEN

After implementing the coordinator state machine, gateway seam, and TTL registry:

```text
./gradlew :server:test --tests 'de.aidadventure.server.prefill.PrefillCoordinatorTest.turn drops pending prefill and follows active prefill on its slot'
```

Result: passed.

The full suite then produced fresh results with five Task 3 tests and zero failures:

- `PrefillCoordinatorTest`: 4 tests, 0 failures
- `PrefillRegistryTest`: 1 test, 0 failures

## Verification

```text
./gradlew check --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL` (27 actionable tasks; 2 executed, 25 up-to-date).

## Concern

Task 4 route and dependency-injection wiring remains intentionally out of scope for Task 3. No runtime server or browser process was started for this task.

## Concurrency review completion

The follow-up concurrency review is complete:

- Every coordinator created by one registry receives the same process-local lease manager. Lease ownership is partitioned by llama.cpp port, and leased slot IDs are excluded before the gateway erases a newly acquired slot.
- A turn is represented by one coordinator-owned lazy job and a single-use flow. Collector cancellation and coordinator shutdown cancel and join that job before the slot is released.
- Prefill-to-turn handoff occurs only while the matching generation and state still own the lease. Closing invalidates the generation, cancels queued or running turn execution, joins active work, and only then releases.
- TTL cleanup atomically claims only `Idle`, cache-ready, or release-pending coordinators. Active prefill, waiting-turn, and running-turn coordinators remain registered.
- Failed slot erase keeps both manager ownership and coordinator release state. A later close or registry cleanup retries the same release instead of making the slot available locally.

Review RED command:

```text
./gradlew :server:test --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --tests de.aidadventure.server.prefill.PrefillRegistryTest --tests de.aidadventure.server.llamacpp.LlamaCppSlotTest
```

Result: test compilation failed on the missing exclusion-aware slot API, registry-shared factory API, idle-close claim, and updated gateway seam.

Review GREEN command:

```text
./gradlew :server:test --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --tests de.aidadventure.server.prefill.PrefillRegistryTest --tests de.aidadventure.server.llamacpp.LlamaCppSlotTest --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest
BUILD SUCCESSFUL in 11s
```

The focused run passed 19 tests with zero failures. The fresh full gate also passed:

```text
./gradlew check
BUILD SUCCESSFUL in 10s
27 actionable tasks: 2 executed, 25 up-to-date
```

The build retains the pre-existing Kotlin/JS plugin deprecation warning.
