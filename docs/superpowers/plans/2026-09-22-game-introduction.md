# Game Introduction Implementation Plan

**Goal:** Start each new game with a persisted, streamed LLM introduction.
**Architecture:** Reuse TurnSubmission and all existing API/cache machinery; a
browser-owned pending flag controls automatic introduction and retry. Hide the
start instruction only in the transcript, never in request history.
**Tech stack:** Kotlin/JS React, Kotlin serialization, existing Kotlin server.

## Constraints

- Keep server stateless, the same session ID, canonical history and retained slot.
- Preserve compatibility with saved sessions lacking the new fields.
- Complete verification with `./gradlew check` including dependency installation.

## Steps

- [x] Add a failing browser test to AdventureAppNavigationTest: New session must
  call play automatically, stream an introduction and persist it.
- [x] Add `introductionPending: Boolean = false` to GameSession and
  `hidden: Boolean = false` to GameMessage in Models.kt. Set pending true only
  in newGameSession; exclude hidden messages from the transcript.
- [x] Extend TurnSubmission with submitIntroduction: require a pending empty
  session, submit a fixed introduction instruction, hide its history message,
  clear pending on success, and restore the original display on failure.
- [x] In PlayView, warm up then introduce on mount when pending. Reuse submission
  callbacks, keep input disabled until saved, and offer Retry introduction after
  errors. Preserve existing warmup for resumed completed/legacy games.
- [x] Add tests for partial streaming, retry, disabled input, completed resume,
  unchanged canonical history in warmup/prefill/turn mappers and old saved data.
- [x] Run `./gradlew check`, review the diff and run `git diff --check`.

## Verification

The browser regression first failed with `Expected <1>, actual <0>` automatic
turn calls, then passed after implementation. Browser coverage includes streaming,
input locking through secondary completion, retry, completed and pending resume,
hidden canonical history, storage failures, empty completions and legacy data.

`./gradlew check` executes all dependency-install tasks and the cache-contract
suite. The initial full run passed 63 browser tests and 242 of 243 server tests,
including all 10 PromptFactoryTest and 30 PrefillCoordinatorTest cases. The sole
server failure is pre-existing in HEAD: WorldStateResolverTest expects the old
"Retrospectively update..." prompt while the committed resolver uses "Reply with
a short sentence...". Neither server file was changed for this feature.

Independent code review found no actionable production defect; its suggested
pending-session resume coverage was added before final verification.

Final verification with `./gradlew check --continue` also completed the web checks
following the known server failure: 64 browser tests passed, 242 of 243 server
tests passed; the same pre-existing prompt assertion was the only failure.
`git diff --check` passed. No application server or LLM runtime was started.
