# Task 3 Report: Per-session prefill coordinator and bounded registry

## Scope

Implemented the Task 3 coordinator and registry only:

- `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt`
- `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillRegistry.kt`
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
