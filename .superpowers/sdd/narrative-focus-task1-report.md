# Narrative Focus — Task 1 report

## Scope and plan correction

Implemented Task 1 of `docs/superpowers/plans/2026-07-30-narrative-focus.md`.

The original task-file scope was insufficient to compile: replacing the shared
server `WorldState` field left `WorldStateResolver` and its API/test fixtures
referencing `unresolvedSituation`. The coordinating agent approved the minimal
plan correction: mechanically rename those cross-module references and their
existing JSON fixtures to `narrativeFocus`, without adopting Task 2's new
retrospective prompt wording or changing the resolver's independent resolution
flow.

## Changes

- Server and browser `WorldState` now expose `location` and `narrativeFocus`.
- Snapshots render `NARRATIVE FOCUS: <focus>` only for nonblank focus.
- Persisted browser sessions migrate both legacy keys:
  - `unresolvedSituation` takes precedence over `activeThread` when no current
    `narrativeFocus` exists.
  - an already present `narrativeFocus` wins over either legacy key.
  - legacy keys are always removed before decode.
- The existing resolver schema/result JSON and API SSE fixtures use the renamed
  property, preserving pre-Task-2 prompt and flow behavior.

## TDD evidence

### RED

After adding the new model, snapshot, JSON, and migration expectations, ran:

```text
./gradlew :server:test --tests de.aidadventure.server.worldstate.WorldStateTest :web:browserTest --tests de.aidadventure.web.WorldStatePopoverTest
```

Result: failed as expected at compile time. Both test modules reported
`No parameter with name 'narrativeFocus' found` because the production models
still exposed `unresolvedSituation`.

### GREEN

After the production changes, reran the same focused command (with
`--console=plain` for the captured final result):

```text
BUILD SUCCESSFUL in 1s
26 actionable tasks: 1 executed, 25 up-to-date
```

## Full verification

```text
./gradlew check --console=plain
BUILD SUCCESSFUL in 23s
27 actionable tasks: 4 executed, 23 up-to-date
```

The build emitted pre-existing/toolchain warnings from the deprecated
`kotlin-js` plugin, Webpack bundle-size recommendations, Lucene native access,
and Byte Buddy Unsafe access; none were test failures.

`git diff --check` also completed without whitespace errors.

## Files changed

- `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldState.kt`
- `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt`
- `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`
- `server/src/test/kotlin/de/aidadventure/server/model/ApiModelsTest.kt`
- `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateResolverTest.kt`
- `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateTest.kt`
- `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt`
- `web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt`
- `web/src/jsTest/kotlin/de/aidadventure/web/WorldStatePopoverTest.kt`

## Self-review

- Confirmed no legacy key remains in productive serialized state or SSE
  expectations; remaining occurrences are migration inputs and test fixtures.
- Confirmed current `narrativeFocus` is preserved during migration, including
  when both legacy values are present.
- Confirmed the resolver's prompt text and resolution sequencing were not
  altered; only the structured field/key plumbing was mechanically renamed.
- No agent-started runtime processes were created.
