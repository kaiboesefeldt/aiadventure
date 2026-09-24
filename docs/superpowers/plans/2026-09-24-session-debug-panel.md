# Session Debug Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace inline play-view diagnostics with a persistent, read-only session debug panel that records turn processing and links to transcript locations.

**Architecture:** The server will add structured diagnostics to the existing turn SSE stream without persisting session data or logging prompt/game text. The browser will persist those events in a dedicated IndexedDB store keyed by session, render them in the right-side panel, and connect each turn's debug entry with its transcript messages.

**Tech Stack:** Kotlin Multiplatform (JVM server and Kotlin/JS web client), Ktor SSE, React Kotlin wrappers, kotlinx.serialization, browser IndexedDB, existing Gradle test tasks.

## Global Constraints

- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
- Player input and game-master output text must not appear in debug entries.
- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Preserve the prompt prefix, retained-slot, and cache contracts for new, resumed, and following turns.
- A prefill and its turn must use the same `sessionId`; only llama.cpp's returned `tokens_cached` and `tokens_evaluated` metrics prove cache reuse.
- Log operational gateway/model failures with request IDs only. Do not broaden server logs to include prompts, history, actions, or complete LLM responses.
- Clearing diagnostics removes only the current session's debug events; deleting a session or its scenario removes associated debug events too.

---

## File Structure

- `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`: typed diagnostic SSE payload and event models.
- `server/src/main/kotlin/de/aidadventure/server/Application.kt`: serialize diagnostic events, initial request-state diagnostics, signal application, world-state result, and terminal status.
- `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`: collect llama.cpp timings/token/context measurements and background completion outcomes without changing prompt construction.
- `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt` and background resolver/gateway seams: surface per-turn fragment resolution, world-state, and context-compression events at the point those operations complete.
- `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`, gateway and coordinator tests: cover event payloads, ordering, and data exclusions.
- `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`: decode diagnostic SSE frames and deliver them to turn submission.
- `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt`: persistable diagnostic event, turn identity, and metrics models.
- `web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt`: add the diagnostics object store/repository and atomic lifecycle cleanup.
- `web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt`: assign stable per-turn identity, associate diagnostics with transcript message positions, and persist partial/error/cancelled turns.
- `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`: remove inline controls/metrics, render global and turn diagnostics, add bidirectional transcript navigation.
- `web/src/jsMain/resources/app.css`: right-edge overlay, readable event cards, responsive layout, expanded/collapsed content, and transcript navigation affordances.
- `web/src/jsTest/kotlin/de/aidadventure/web/api/TurnStreamProcessorTest.kt`, persistence tests, and navigation/UI tests: cover decoding, durable isolation/cleanup, and interaction behavior.

## Task 1: Capture turn diagnostics on the server

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt` and the existing background-operation seams it owns
- Test: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt` plus focused gateway/coordinator tests

**Interfaces:**
- Produce a serializable `StreamDiagnostic` with a stable event kind, processing stage, human-readable fields, optional transcript-role reference, and optional metrics. Do not include player-action or game-master-output content.
- Carry diagnostics through the existing `TurnStreamEvent` flow and SSE stream as a `diagnostic` event. Keep existing `token`, `fragment`, `done`, and `error` event behavior compatible.
- Metrics must report only values supplied or measured by llama.cpp/client code: prompt/context token counts, predicted token counts, cache counts, elapsed milliseconds, and rates when available. Do not infer a cache hit from anything except returned `tokens_cached`/`tokens_evaluated`.

- [ ] Add server tests that assert initial turn state, selected fragment/signals, applied flag changes, accepted world state, context-compression results, and background LLM outcomes are emitted as ordered structured diagnostics.
- [ ] Assert serialized diagnostics do not contain player action or generated game-master text, including failure/cancellation cases.
- [ ] Decode llama.cpp final timing fields where present and verify missing measurements remain absent rather than fabricated.
- [ ] Emit diagnostics at existing operation boundaries; do not add diagnostic text to `PromptFactory` inputs or operational logs.
- [ ] Keep existing SSE event compatibility and run focused `:server:test` cases.

## Task 2: Decode and persist diagnostics by session

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/model/Models.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/AdventureDatabase.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt`
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/api/TurnStreamProcessorTest.kt` and new/related persistence and `TurnSubmissionTest.kt` cases

**Interfaces:**
- Decode `diagnostic` SSE frames into the typed diagnostic model and add a diagnostic callback to the turn API path; preserve default/no-op compatibility for test fakes where possible.
- Persist `DebugEvent(id, sessionId, turnId, sequence, createdAt, kind, stage, fields, metrics, transcriptRole)` independently of `GameSession`.
- Expose repository operations to list events for one session, append an event, clear one session's events, and delete events for a set of sessions.
- Generate a unique turn ID before submission and a monotonically increasing event sequence for that turn. Persist events as they arrive so interrupted turns retain partial diagnostics.

- [ ] Add stream-decoder tests for diagnostic ordering and compatibility with old streams that contain no diagnostics.
- [ ] Add IndexedDB tests for append/list ordering, isolation between sessions, clear-one-session behavior, and persistence after reopening the database.
- [ ] Add lifecycle tests proving deleting a session removes its events and deleting a scenario removes events for all its sessions in the same IndexedDB transaction.
- [ ] Add submission tests proving diagnostics are persisted on completion, stream failure, and cancellation without storing player or game-master message bodies.
- [ ] Run focused `./gradlew :web:compileTestKotlinJs` and `./gradlew :web:browserTest` serially for this checkout.

## Task 3: Render the read-only debug panel and global information

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Modify: `web/src/jsMain/resources/app.css`
- Modify: `web/src/jsTest/kotlin/de/aidadventure/web/WorldStatePopoverTest.kt` and `AdventureAppNavigationTest.kt`

**Interfaces:**
- The Play view receives a diagnostics repository, session events, and clear/refresh callbacks.
- The panel is opened by a right-edge overlay button and closed by X; the only mutating action is `Clear debug log` for the current session.
- Global sections render current flags, an expandable parsed frontmatter tree, and an expandable system prompt. Turn entries render typed fields and metrics in human-readable labels; expandable detail must not reveal player input or game-master output.

- [ ] Add UI tests asserting the old `World State`, per-message `Info`, and main-area token metrics controls are absent and the panel open/close controls are present.
- [ ] Add rendering tests for flags, YAML tree, system prompt, context-compression details, background results, signal-trigger effects, and token/context metrics.
- [ ] Add clear-action tests proving only debug events for the active session are removed.
- [ ] Implement newest-at-bottom ordering and bottom-aware auto-scroll; preserve the reader's position when new entries arrive while scrolled up.
- [ ] Style the overlay and event hierarchy for readable scanning, keyboard-accessible controls, and narrow viewports; run focused web tests.

## Task 4: Link transcript and diagnostics, then integrate

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt`
- Modify: `web/src/jsMain/resources/app.css`
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/AdventureAppNavigationTest.kt` and relevant `TurnSubmissionTest.kt`

**Interfaces:**
- Each persisted turn records stable transcript indexes/roles for the player and game-master messages without storing message contents in diagnostics.
- Debug-to-transcript navigation scrolls the main transcript to the matching message. Transcript-to-debug navigation opens the panel and scrolls its log to the matching turn event.

- [ ] Add tests for both navigation directions, including resumed sessions and turns with hidden introduction input.
- [ ] Verify references remain valid when a turn is partial, cancelled before output, or resumed from persisted session history.
- [ ] Run the full `./gradlew check` without excluding dependency-install tasks, then run `git diff --check` separately and inspect the complete diff.

## Self-review

- Spec coverage: global flags/frontmatter/system prompt are Task 3; all turn state/background outcomes/context compression/metrics are Task 1 and Task 3; durable per-session persistence, clear, and cascading deletion are Task 2; two-way navigation is Task 4; overlay, read-only behavior, and conditional auto-scroll are Task 3; cache and logging invariants are global constraints and Task 1.
- Placeholder scan: no TBD/TODO or unspecified follow-up steps remain.
- Type consistency: server `StreamDiagnostic` is decoded into a web diagnostic model; web `DebugEvent` adds `sessionId`, `turnId`, sequence, timestamp, and transcript role before persistence. Repository operations are session-keyed and do not alter `GameSession`.
