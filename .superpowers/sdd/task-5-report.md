# Task 5 Report: llama.cpp connection control

## Status

Implemented and committed the two-stage llama.cpp model-selection flow.

Implementation commit: `9ca3b9f feat(web): add llama.cpp connection control`.

Resume audit (2026-07-15): the UI and test changes were already present in that
commit; only this tracked report remained staged. The implementation was kept
intact and checked against the Task 5 brief before fresh verification.

## Implementation

- Added the pure, testable `LlamaCppConnectionState` with `portText`, `modelId`, `checked`, `error`, editable-port and start-click derived properties, plus transition helpers.
- New connections default to port `8081`.
- The first llama.cpp click validates the port, loads `api.llamaCppModels(port)`, records the first model, disables the port, and changes the button to the session-start action.
- The second click saves a new `LLAMA_CPP` session or updates the selected saved llama.cpp session with the discovered model and port, then enters play.
- Discovery, validation, and persistence failures expose a client-safe message and return the control to an editable-port state.
- Ollama model buttons continue to create sessions in one click and saved Ollama sessions continue to open play directly.
- Saved llama.cpp sessions now reopen the scenario detail view with their saved port and model state, so the user reconnects through the editable control before play resumes.

## Test-first evidence

1. Added `LlamaCppConnectionStateTest` before the helper implementation.
2. `./gradlew :web:compileTestKotlinJs --console=plain` failed as expected with unresolved `LlamaCppConnectionState` references.
3. Added the helper and UI wiring, then reran the compile gate successfully.

## Verification

```text
./gradlew :web:compileTestKotlinJs --console=plain
BUILD SUCCESSFUL in 6s

timeout 55s ./gradlew :web:browserTest --rerun-tasks --console=plain
BUILD SUCCESSFUL in 14s
18 actionable tasks: 18 executed
```

The browser suite completed within the 55-second guard after recompiling the
test bundle, including the new pure-state test. It did not stall.

## Concerns

- The build continues to emit its pre-existing Kotlin `kotlin-js` deprecation warning and existing `GlobalScope` delicate-API warnings. This task does not add either warning.
- `:web:jsTest` from the original brief is not a task in this single-target Kotlin/JS build. The requested working tasks are `:web:compileTestKotlinJs` and `:web:browserTest`.
- The forced browser run also emitted Node's existing `[DEP0169]` `url.parse()` deprecation warning during `kotlinNpmInstall`; it did not fail the build.

## Review fix follow-up (2026-07-15)

- The second-stage llama.cpp start now sets `llamaCppPending` before invoking
  the start callback, rejects clicks while pending, and clears pending from the
  completion callback or a synchronous start failure.
- Added a testable start guard: a pending start cannot invoke a second session
  start, and a failed reconnect is explicitly asserted as not startable.

Test-first evidence:

```text
./gradlew :web:compileTestKotlinJs --console=plain
BUILD FAILED in 7s
Unresolved reference 'canStartLlamaCppSession'.
```

## Review fix verification

```text
./gradlew :web:compileTestKotlinJs --console=plain
BUILD SUCCESSFUL in 7s
3 actionable tasks: 2 executed, 1 up-to-date

./gradlew :web:browserTest --console=plain
BUILD SUCCESSFUL in 14s
18 actionable tasks: 4 executed, 14 up-to-date

./gradlew :web:browserProductionWebpack --console=plain
BUILD SUCCESSFUL in 7s
16 actionable tasks: 1 executed, 15 up-to-date
```

All three commands emitted the existing Kotlin `kotlin-js` deprecation
warning. No test, webpack, browser, or app-server process remained after the
checks.
