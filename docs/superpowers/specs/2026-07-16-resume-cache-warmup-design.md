# Resume cache warmup design

## Goal

Opening a saved game session immediately loads its existing prompt context into
the session's llama.cpp slot. While the player reads or types, the server
evaluates the deterministic prefix once. A later prefill or turn appends only
the new player action when llama.cpp can reuse the cache.

## Resume flow

The play view uses the persisted game-session ID as its stable live cache ID.
On entering a resumed session, it sends one silent warmup request containing
the model, llama.cpp port, scenario, and saved message history, but no new
player action. The request does not add a transcript entry, disable input, or
surface success metrics.

The server renders only the game-master instruction, scenario, and history
for that request. This preserves the exact prompt prefix ending at the latest
stored message. It must not synthesize an empty user action: chat-template
role delimiters after an empty action are not a prefix of a later real action.

## Coordination

Warmup uses the existing per-session coordinator and a leased llama.cpp slot.
It shares the same running/pending/turn serialization as a typing prefill. If
the player sends before warmup finishes, the turn waits for it and then runs on
the same slot. If the player starts typing, the newest typing prefill replaces
any pending work after the warmup; it never starts concurrently on the slot.

The coordinator retains a successful warmup lease as `Ready`, matching the
completed-turn cache behavior. TTL expiry, session removal, gateway failure,
and coordinator close still release and erase the slot.

This retained slot is a required performance contract. A llama.cpp-free
coordinator test must prove that both a new session and a resumed session run
their warmup and first turn through one acquisition and one unchanged slot,
with no release before explicit cleanup.

## API and validation

Add a dedicated warmup request and endpoint rather than overloading prefill
with a blank action. The request validates the same session ID, model, port,
scenario, and history bounds as a turn, but intentionally has no player-action
field. Operational logs remain request-ID-only and never include prompt or
game content.

## Tests

Cover the following behavior:

- Browser resume submits one warmup with the persisted session ID and saved
  context.
- A warmup prompt contains only instruction, scenario, and history.
- A turn submitted during warmup waits and receives the warmup's slot.
- A completed warmup retains its slot for the next prefill or turn.
- New and resumed warmups keep one acquired slot warm through their first
  turn, without a release or reacquisition between the two operations.
