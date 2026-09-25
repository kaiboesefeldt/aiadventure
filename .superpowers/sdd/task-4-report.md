# Task 4 review

## Scope

- Plan: `docs/superpowers/plans/2026-07-15-llama-cpp-prefill-cache.md`, Task 4 (`L280-L330`).
- Range: `f781a81..0c4be0e`.
- Verdict: **REQUEST CHANGES**.
- Tests: not rerun. The concrete doubts below are not exercised by the current route fakes, so rerunning the existing suite would not resolve them.

## Findings

### P1 — Production never runs registry TTL cleanup

`server/src/main/kotlin/de/aidadventure/server/Application.kt:L68-L71`: `adventureModule` omits the Task 4 `clock` input and constructs the registry, but neither route calls `removeIdle`; `Application.kt:L107-L109` and `L133-L134` only call `coordinatorFor`. `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillRegistry.kt:L46-L62` shows that expiry and coordinator closure happen only when `removeIdle` is explicitly invoked. Consequently, production entries and their llama.cpp slot leases remain for the process lifetime, so the registry is not bounded and inactive sessions can permanently exhaust available slots. Inject/use the clock and invoke lifecycle- or request-driven idle cleanup before acquiring coordinators, with safe failure handling.

### P1 — `/api/turn` claims coordinator state before the SSE writer owns it

`docs/superpowers/plans/2026-07-15-llama-cpp-prefill-cache.md:L318` requires `beginTurn` inside the existing SSE writer. Instead, `server/src/main/kotlin/de/aidadventure/server/Application.kt:L133-L139` calls `beginTurn` first and only then installs the writer. `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt:L106-L124` moves the coordinator to `TurnWaiting`/`Turning` when `beginTurn` returns, while cleanup is tied to collecting the returned flow. If response startup or the client connection ends before collection begins, the coordinator can retain turn ownership and its slot indefinitely. Move coordinator lookup, `beginTurn`, and collection into the writer's guarded lifecycle so failures remain client-safe SSE errors and collector cancellation releases ownership.

### P1 — Synchronous slot I/O bypasses safe route error mapping

`server/src/main/kotlin/de/aidadventure/server/Application.kt:L108-L112` catches only `RuntimeException` around prefill submission; the analogous turn-acquisition guard at `L133-L137` does the same. Both paths can acquire a slot synchronously (`PrefillCoordinator.kt:L72-L82`, `L172-L174`, and `L190-L195`), and `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt:L59-L66` uses `HttpClient.send`, whose refused/failed connection path is `IOException`, not `RuntimeException`. A llama.cpp disconnect during slot acquisition therefore escapes the request-ID-safe `llama_cpp_unavailable` mapping instead of returning the intended safe 503/SSE error. Handle `IOException` explicitly (while preserving cancellation), and add focused refused-connection coverage for prefill and turn slot acquisition.

## Requirement audit

### PASS — llama.cpp-only model endpoint

- `server/src/main/kotlin/de/aidadventure/server/Application.kt:L91-L99` validates `port` and delegates `/api/models` to `LlamaCppGateway.availableModels(port)` with safe 503 mapping.
- `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt:L61-L82` covers successful discovery and missing-port rejection.
- No provider branch or Ollama gateway remains in the route.

### PARTIAL — `POST /api/prefill` returns 202 without awaiting evaluation

- `server/src/main/kotlin/de/aidadventure/server/Application.kt:L101-L112` validates before registry lookup and returns `202 Accepted` with `PrefillAccepted` after `submit`.
- `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt:L172-L187` launches evaluation asynchronously, so the happy path does not wait for prompt evaluation.
- Safe failure behavior is incomplete because synchronous slot-acquisition `IOException` is unhandled (finding 3).

### PARTIAL — coordinator-owned SSE turn

- `server/src/main/kotlin/de/aidadventure/server/Application.kt:L120-L147` validates the selected model, resolves the coordinator by `(sessionId, modelId, port)`, and emits `token`/`done` SSE events.
- Coordinator ownership begins outside the SSE writer, violating the Task 4 lifecycle requirement (finding 2).

### PASS — validation and handled-path logging

- `server/src/main/kotlin/de/aidadventure/server/Application.kt:L193-L220` rejects negative revisions, blank/oversized session IDs, invalid model/port/scenario/action/history data before registry lookup; `L228-L233` defines the required bounds.
- `server/src/main/kotlin/de/aidadventure/server/Application.kt:L149-L155` and `L172-L178` log request ID, operation, safe result, and exception class only; no scenario, history, action, prompt, or model output is logged.
- `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillRegistry.kt:L73-L91` logs only a SHA-256-derived opaque coordinator key.
- Unhandled slot `IOException` still bypasses this route-level discipline (finding 3).

### PASS — Ollama server removal

- `server/build.gradle.kts:L14-L27` contains no `langchain4j-ollama` dependency; the remaining direct `langchain4j` dependency supplies message types used by the llama.cpp prompt path.
- `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt:L12-L36` contains llama.cpp-only request DTOs and no `ModelProvider`.
- Range deletions include `server/.../ollama/OllamaGateway.kt` and all four requested Ollama test files.
- `rg -n -i "ollama|ModelProvider|langchain4j-ollama" server` produced no source/test/build matches.

## Review verification

- `git diff --check f781a81..0c4be0e`: clean.
- Worktree mutation is limited to this report; the pre-existing `.superpowers/sdd/task-2-report.md` modification was preserved.

## Review-finding fixes (2026-07-16)

- `adventureModule` now receives an injectable clock and runs `removeIdle(clock())` before prefill coordinator use and from the active SSE writer before turn coordinator ownership. This gives the registry a request-driven, safe cleanup cadence.
- `/api/turn` now writes its SSE connection marker before resolving the coordinator and calling `beginTurn`; writer setup failure therefore cannot claim a turn. Cancellation continues to propagate rather than being converted into an SSE error.
- Prefill maps `IOException` from synchronous cleanup or slot acquisition to the existing request-ID-safe `503 llama_cpp_unavailable` response. Turn does the equivalent from its SSE writer through the existing safe `error` event.

### Test-first evidence

1. The injected-clock cleanup regression initially failed to compile because `adventureModule` had no `clock` input.
2. The prefill slot-acquisition I/O regression initially failed because the `IOException` bypassed the runtime-only route mapping.
3. The turn slot-acquisition I/O regression initially failed because ownership was claimed before the SSE writer and no cleanup ran; the route did not emit the safe SSE error.
4. Each focused regression passed after its minimal route change.

### Verification

- `./gradlew :server:test --tests de.aidadventure.server.ApplicationTest --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --tests de.aidadventure.server.prefill.PrefillRegistryTest` — passed.

# Task 4: transcript and diagnostic navigation

## Implementation

- Added nullable `DebugEvent.transcriptIndex` with a default of `null`, so older persisted events decode unchanged. The index counts visible transcript articles; hidden introduction input and runtime fragments do not consume indexes.
- `TurnSubmission` assigns references without storing transcript text in diagnostics. Current player diagnostics target the current visible action, `source=prior_turn` targets the last visible prior player, and game-master diagnostics target output only after output exists. Introduction diagnostics target visible output once it exists; before that, both reference and role are null.
- Visible transcript articles and debug events have stable IDs. An accessible button on each linked event closes the panel and scrolls to the transcript article. An accessible button on a linked transcript article opens the panel and scrolls to its event. Links are hidden when the referenced message is absent or its role does not match.

## TDD evidence

- Added failing navigation and submission tests first. Initial focused browser run: 29 tests, 6 expected failures for missing references and controls.
- Added the model field and implementation. Focused browser run then passed: 29 tests.
- Added a stricter introduction test requiring a null role before output. Isolated run failed as expected, then passed after the role fix.
- Tests cover both navigation directions, resumed sessions, prior-turn references, hidden introduction input, missing output, partial stopped turns, later runtime-fragment insertion, legacy event decoding, and absence of message text in diagnostics.

## Verification

- `./gradlew :web:compileTestKotlinJs`: passed.
- `./gradlew :web:browserTest`: passed.
- `./gradlew check`: passed on the first and final runs. An intervening run failed one untouched server test, `PrefillCoordinatorTest.waiting turn receives prior rerank failure before its terminal error`, at line 87; an isolated rerun failed too. The final full run passed. This server test is timing sensitive; Task 4 changed only web files.
- `git diff --check`: passed separately after the full check.

## Self-review

- Scope is limited to navigation, the diagnostic reference field, submission mapping, styling for the new controls, and relevant tests.
- References use only indexes and roles. No message body is copied into `DebugEvent`.
- A diagnostic emitted before any game-master output has no output reference. A failed turn can retain diagnostics while its transient transcript is discarded; the UI shows no navigation control unless the referenced visible message exists.

## Follow-up: durable message identity

The initial index-and-role check above had a stale-link gap: an unsaved player
message from a failed or cancelled turn could be replaced by a retry at the
same visible index. The old event would then point to unrelated text.

- Added backward-compatible nullable `GameMessage.messageId` and
  `DebugEvent.transcriptMessageId`. Each submitted turn gets distinct player
  and game-master IDs. Streamed, partial, and final copies of its game-master
  message retain the same ID.
- A `source=prior_turn` event copies the prior visible player's ID when one
  exists. Introduction diagnostics reference only the visible game-master
  output after output begins. The index remains diagnostic metadata; both
  navigation controls require an exact visible message ID and matching role.
  Legacy messages/events without IDs simply have no navigation control.
- Test-first focused run: 34 tests, 3 expected failures for failed-turn retry,
  cancelled-before-output retry, and stale navigation after resume. After the
  implementation, the same focused suite passed. The retry test serializes
  and decodes the completed session before checking IDs. Existing successful,
  resumed, prior-turn, partial-output, and introduction tests still pass.
- Serial verification: `./gradlew :web:compileTestKotlinJs`,
  `./gradlew :web:browserTest`, and `./gradlew check` all passed.
- Self-review: only web model, submission, navigation, and tests changed;
  no server code or assertions changed. Diagnostic events still contain IDs,
  indexes, and roles without copies of transcript text.
