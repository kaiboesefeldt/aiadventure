# llama.cpp prefill cache design

## Goal

Reduce time to first game-master token by evaluating the complete, current
player action while the player is typing. The final turn must reuse that
prompt/KV-cache work when possible. The feature is designed around a
per-game-session cache context and removes Ollama from the application so the
cache contract is explicit and testable.

## Decisions

- `llama.cpp`/`llama-server` is the sole supported model backend. Remove
  Ollama discovery, configuration, gateway code, UI, tests, persisted provider
  compatibility, and dependencies as part of the implementation.
- Use native llama.cpp HTTP requests for cache-aware prefill and normal turns;
  do not route these requests through LangChain4j. LangChain4j did expose
  Ollama `numPredict`, but does not provide a portable, explicit prefill,
  cache-handle, or slot-affinity contract.
- The cache context belongs to one logical game session. It is ephemeral
  server state, never browser-persisted game data.
- A prefill evaluates a prompt but never generates a game-master response and
  never changes session history.
- At most one prefill is running per game session, with at most one newer
  prefill remembered as replaceable pending work.
- A regular turn wins over pending prefill work. It allows an active prefill
  to finish, drops pending prefill work, then generates on the same cache
  context.

## Prompt and cache invariant

The exact logical prompt order remains:

1. Fixed game-master instruction.
2. Original scenario Markdown.
3. Historic player and game-master messages in chronological order.
4. Current complete player action.

No timestamps, request IDs, status strings, or other dynamic data may be
inserted into this sequence. Prefill and regular turns must pass the same
rendered prompt representation to llama.cpp for a given session, history, and
action. The model output remains untrusted text.

## llama.cpp contract

The gateway owns all llama.cpp calls; the browser never connects to llama.cpp
directly. It uses a native cache-aware contract with a stable per-session slot
and prompt caching enabled. The final choice of native endpoint is hidden
behind the gateway, but the selected endpoint must support equivalent fields:

```text
prompt or messages
id_slot = stable session slot
cache_prompt = true
stream = true for a regular turn
stream = false or prompt-only mode for prefill
```

The preferred path is llama.cpp's native completion contract because it
documents `id_slot` and `cache_prompt` explicitly. If the deployed
llama.cpp version requires its chat-template endpoint, the gateway must prove
through an integration test that the same cache and slot options are accepted
and that the rendered prompt is identical for prefill and turn.

The gateway reads llama.cpp timing data when supplied. A cache hit is reported
only from backend-provided values such as `cache_n` and `prompt_n`; no cache
claim is inferred from a faster response.

## API contract

`POST /api/prefill` accepts the current full request context plus a client
session ID and a monotonically increasing revision:

```text
{
  sessionId,
  revision,
  modelId,
  llamaCppPort,
  scenarioMarkdown,
  history,
  playerAction
}
```

The server validates the same model, port, scenario, history, and action size
limits as a turn. It returns `202 Accepted` without waiting for prompt
evaluation to complete. Its small response states whether the revision was
started, retained as the newest pending prefill, ignored as stale, or ignored
because a turn is in progress.

`POST /api/turn` retains SSE token, done, and error events. It receives the
same client session ID so it can claim the exact coordinator and slot used by
prefill. It remains the only endpoint allowed to generate or persist a
game-master message.

The client session ID is an opaque browser-generated identifier. It is used
only for coordinator lookup and is not logged verbatim.

## Prefill coordinator

The server has one coroutine-safe `PrefillCoordinator` per live game session,
keyed by `sessionId + modelId + llamaCppPort`. Each coordinator owns a mutex,
a stable llama.cpp slot lease, its latest accepted revision, and this state:

```text
Idle
Running(activePrefill, pendingPrefill?)
TurnWaitingForPrefill(turnRequest)
TurnRunning(turnRequest)
```

Transitions are defined as follows:

1. `Idle + prefill(revision)` starts the request and becomes `Running`.
2. `Running + newer prefill` replaces `pendingPrefill`; equal or older
   revisions are ignored.
3. `Running + turn` discards `pendingPrefill`, records the turn, and becomes
   `TurnWaitingForPrefill`.
4. An active prefill completing in `TurnWaitingForPrefill` starts the turn on
   the same slot without admitting another prefill.
5. An active prefill completing in `Running` starts only the latest pending
   prefill, or returns to `Idle`.
6. `TurnRunning` ignores all prefill requests. Completion or a safe failure
   returns to `Idle`.
7. Timeout, gateway failure, coordinator TTL expiry, session deletion, or a
   llama.cpp restart clears the lease and returns to `Idle`. A submitted turn
   receives a client-safe error if it cannot proceed.

Generation/revision tokens guard every asynchronous completion. No late
prefill result or failure may change the state of a newer prefill or a turn.
No two upstream requests may target the same session slot concurrently.

## Slot management and operating limits

The server obtains and retains a distinct llama.cpp slot for each live game
session. A session therefore does not require llama.cpp to run with
`--parallel 1`; parallel slots can retain cache contexts for independent game
sessions. If no slot is available, prefill is rejected or skipped safely and a
regular turn receives an explicit client-safe busy error rather than borrowing
another session's slot.

Slot availability and endpoint capabilities are checked when a session starts.
The implementation documents the llama.cpp launch requirements, including
enabled slot inspection where needed. Server restart invalidates all leases;
the next request creates a fresh coordinator and works without cache reuse.

Coordinators use a bounded idle TTL and are removed after inactivity. This
caps server memory and releases their llama.cpp slots. They hold no scenario,
history, action, or generated text after work completes.

## Browser behavior

The play view creates an opaque `sessionId` for each live game session and
retains it while that session is active. It sends the full current text and a
strictly increasing revision after every edit, coalesced by a 100--200 ms
debounce. The server-side coordinator is authoritative, so rapid delivery or
out-of-order browser requests remains safe.

Prefill is silent: it creates no transcript entry, metrics display, or
persistent browser mutation. It stops being sent while a regular turn is
pending. Prefill failure does not disable typing or sending; the next regular
turn remains valid without a cache hit.

## Logging and failures

Operational logs contain request ID, opaque hashed session key, operation,
state transition, and safe failure category only. They never contain scenario
Markdown, history, action text, rendered prompt, or LLM output. Client errors
remain generic and safe.

## Verification

Unit and integration tests cover:

- deterministic equivalence of prefill and regular-turn prompt rendering;
- native llama.cpp requests carrying the same slot and `cache_prompt` flag;
- all coordinator transitions, newest-pending replacement, turn priority, and
  stale revision rejection;
- no concurrent upstream request for one session slot;
- timeout, gateway failure, idle cleanup, session deletion, unavailable slot,
  and llama.cpp restart handling;
- browser debounce, full-text/revision requests, and suppression during a
  regular turn;
- removal of Ollama routes, configuration, dependencies, UI, and persistence
  fields.

The live integration test must compare an otherwise identical turn with and
without prior completed prefill. It passes only when llama.cpp reports a
backend-provided cache reuse measurement for the prefilled turn. The complete
project verification remains:

```bash
./gradlew check
```

## Out of scope

- Persistent server-side scenario or game-session storage.
- Direct browser access to llama.cpp.
- Multiple llama.cpp hosts or remote host configuration.
- Cache metrics not returned by llama.cpp.
- Partial or speculative game-master output during typing.
