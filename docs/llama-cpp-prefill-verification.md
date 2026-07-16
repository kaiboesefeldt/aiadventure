# llama.cpp prefill cache verification

This checklist verifies cache reuse without recording game content or model
output.

## Setup

1. Start llama.cpp with at least two slots:

   ```bash
   llama-server --host 127.0.0.1 --port 8081 --model /absolute/path/to/model.gguf --parallel 4 --slots --slot-save-path /tmp/ai-adventure-slots
   ```

2. Start the application with `./gradlew :server:run` and open
   `http://127.0.0.1:8080/`.
   The `--slot-save-path` directory must be writable: the application clears a
   slot before leasing it and after releasing it, preventing one session's
   cached context from being reused by another.
3. Use an existing test scenario or a disposable scenario. Do not copy its
   content into logs, screenshots, issue reports, or this checklist.

## Procedure

1. Start a new game session and note only the request ID and the aggregate
   `tokens_cached` and `tokens_evaluated` counters returned by llama.cpp.
2. Type an action, pause long enough for the browser's debounce, and confirm
   that `POST /api/prefill` returns `202 Accepted`.
3. Confirm that the prefill did not create a transcript message, error, or
   disabled Send control.
4. Send exactly that action. Confirm that its `POST /api/turn` uses the same
   `sessionId` as the prefill, then record only its request ID and aggregate
   cache/evaluated-token counters.
5. In a new session, send an action without pausing for prefill. Record the
   same aggregate counters and compare them with the prefetched case.

Cache reuse is demonstrated only by backend `tokens_cached` and
`tokens_evaluated` values. Timing, an apparent warm model, or a subsequent
turn are not proof.

## Evidence boundary

Never capture or log scenario text, player actions, prompt text, conversation
history, model output, model identifiers, or slot IDs. Evidence contains only
request IDs and aggregate cache/evaluated-token counters.
