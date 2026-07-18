# Fragment Prefill Order Design

## Goal

After a completed LLM response provides enough history to determine the next
runtime fragment, warm that fragment in the session's llama.cpp prompt cache
immediately. The coordinator queues later user-input prefills until that warmup
has finished.

## Scope

This changes only the server post-turn path when the completed history resolves
to a non-empty next fragment. Turns without a fragment retain the current
delayed user-input prefill behavior unchanged. The browser API contract and its
normal debounce behavior remain unchanged.

## Flow

1. The turn route receives the completed token stream and builds completed
   history from the resolved turn history, player action, and generated text.
2. It resolves the next fragment from that completed history.
3. If the fragment is non-empty, it calls the existing coordinator warmup with a
   `WarmupRequest` whose history ends in that fragment, using the same session
   ID and retained slot.
4. While this asynchronous warmup is active, the existing coordinator state
   queues a later user-input prefill and starts it after warmup completion.
5. If no next fragment is resolved, skip steps 2-4 and retain the current path.
6. If fragment resolution or warmup fails, log only request ID, operation, and
   failure class; normal input prefill remains usable.

## Invariants

- All prefill and turn operations retain the same session ID and llama.cpp slot.
- The server sends `done` only after it has started the fragment warmup, so a
  subsequent user-input prefill reaches a coordinator already in `Prefilling`.
- The coordinator's existing pending-prefill replacement continues to coalesce
  input to the latest revision.
- The fragment remains hidden from the ordinary transcript.

## Verification

Add route-level coverage proving a completed turn resolves a next fragment and
submits one warmup whose history contains the complete exchange followed by the
new fragment. Add coordinator coverage proving a later prefill is queued behind
that warmup and starts on the same retained slot. Cover no-fragment fallback and
privacy-safe resolution failure logging.
