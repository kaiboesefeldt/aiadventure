### Task 1: Queue input, combine pre-token sends, and stop token generation on later sends

**Files:**
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt` for enabled controls, Enter handling, queued actions, and turn phase.
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/TurnSubmission.kt` to retain and save the partial streamed answer when explicitly stopped.
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt` to expose cancellation of the active SSE reader while leaving preparation requests untouched.
- Modify `web/src/jsMain/kotlin/de/aidadventure/web/PrefillSubmission.kt` so combined pending input can supersede the prefill before turn generation, with the existing session ID.
- Modify `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`, `server/src/main/kotlin/de/aidadventure/server/Application.kt`, and `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt` to emit a distinct generation-ready event after background preparation and before the first generation request.

**Behavior:**
- Keep the action textarea and Send button enabled except where no player turn is legal (adventure introduction pending); button remains disabled for blank text.
- If a send occurs while a game-master response is streaming, synchronously stop reading the stream, preserve the accumulated partial answer as the official game-master message, persist it with the player action that elicited it, then process the new action as the next turn.
- If a send occurs during background preparation or before the first generated token, queue it; subsequent sends before the first token append to that same player action. Update/restart any in-flight prefill for the combined prompt before generation.
- Do not cancel background resolution while it is running. At the explicit generation-ready boundary, if queued text changed the pending action, restart prefill/turn with the combined action before accepting any token frame.
- Once the first response token is observed, later sends start the next turn after the partial answer is committed.
- Keep existing normal completed-turn behavior and same-session cache contract intact.

**Verification:** Do not alter or weaken existing cache-contract checks. Do not add or run tests in this task; this task is limited to implementation and static diff review.
