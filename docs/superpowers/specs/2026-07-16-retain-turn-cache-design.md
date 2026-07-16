# Retain llama.cpp cache after a generated turn

## Goal

Keep the KV cache created by a completed game-master turn available for the
same live game session. The first prefill after the player starts the next
action must therefore append only the newly typed prompt suffix instead of
evaluating the complete scenario and transcript again.

## Cause

`SessionPrefillCoordinator` currently releases its slot when a turn completes.
Releasing clears the llama.cpp slot, so the next debounced prefill necessarily
re-evaluates the whole prompt. The browser correctly resumes sending prefills
after a turn, but it cannot reuse a cache that the server has erased.

## Design

After a successful turn, the coordinator transitions from `Turning` to
`Ready(lease)` instead of releasing the lease. A later prefill or turn uses
that same lease. The native completion request continues to send
`cache_prompt=true`; llama.cpp compares the next rendered prompt with the
retained slot prompt and evaluates only its unseen suffix.

The retained lease remains bounded by the existing five-minute coordinator
TTL. Coordinator close, TTL expiry, session deletion, gateway failure, and
server restart handling continue to release and erase the slot. A turn still
has exclusive use of its slot while it streams.

No browser API, request shape, prompt ordering, or logging content changes.
An empty-action prefill is deliberately not introduced: chat templates append
role delimiters and a generation prompt, so such a request would not be a
prefix of the next real player action.

## Tests

Add a coordinator test covering: completed turn, then prefill. It must assert
that the prefill uses the turn's slot and that no release occurs between them.
Keep the existing cleanup tests to prove close still releases the retained
slot.
