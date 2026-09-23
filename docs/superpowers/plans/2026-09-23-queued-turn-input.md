# Always-Available Turn Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development to implement this plan task-by-task.

**Goal:** Keep the player action field and Send button available and apply sends according to whether a turn is waiting, streaming tokens, or complete.

**Architecture:** Model a pending action separately from an active stream. Before the first response token, append further submitted text to the pending action and ensure prefill targets that combined text. Once tokens start, abort the active SSE reader, persist the streamed partial game-master answer as the completed official response, then run the queued next action. Waiting background preparation must not be canceled and must delay dispatch while accumulating submitted text.

**Tech Stack:** Kotlin/JS, React Kotlin wrappers, coroutines, Fetch ReadableStream, existing IndexedDB session repository.

## Global Constraints

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Preserve the local cache-contract requirements for new games, resumed games, and following turns: exact prompt-prefix relationship and one retained warm llama.cpp slot.
- A prefill and its turn must use the same `sessionId`; only llama.cpp's returned `tokens_cached` and `tokens_evaluated` metrics prove cache reuse.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.

---

### Task 1: Queue input, combine pre-token sends, and stop token generation on later sends

**Files:**
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt` for enabled controls, Enter handling, queued actions, and turn phase.
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt` to retain and save the partial streamed answer when explicitly stopped.
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt` to expose cancellation of the active SSE reader while leaving preparation requests untouched.
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/PrefillSubmission.kt` so combined pending input can supersede the prefill before turn generation, with the existing session ID.

**Behavior:**
- Keep the action textarea and Send button enabled except where no player turn is legal (adventure introduction pending); button remains disabled for blank text.
- If a send occurs while a game-master response is streaming, synchronously stop reading the stream, preserve the accumulated partial answer as the official game-master message, persist it with the player action that elicited it, then process the new action as the next turn.
- If a send occurs during background preparation or before the first generated token, queue it; subsequent sends before the first token append to that same player action. Update/restart any in-flight prefill for the combined prompt before generation.
- Once the first response token is observed, later sends start the next turn after the partial answer is committed.
- Keep existing normal completed-turn behavior and same-session cache contract intact.

**Verification:** Do not alter or weaken existing cache-contract checks. Do not add or run tests in this task; this task is limited to implementation and static diff review.
