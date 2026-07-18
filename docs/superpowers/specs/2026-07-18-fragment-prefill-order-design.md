# Fragment Prefill Order Design

## Goal

After a completed LLM response produces a runtime fragment, warm that fragment in
the session's llama.cpp prompt cache immediately. Do not submit later user-input
prefills until that fragment warmup has finished.

## Scope

This changes only the post-turn path when a non-empty runtime fragment was
selected. Turns without a fragment retain the current delayed user-input prefill
behavior unchanged.

## Flow

1. A turn completes and the associated runtime fragment is known.
2. If the fragment is non-empty, submit a prefill for the prompt state including
   that fragment, using the existing session ID and retained slot.
3. Hold queued/debounced user-input prefill submissions while this fragment
   prefill is in flight.
4. Once it completes, release the most recent queued user-input prefill through
   the existing submission path.
5. If the fragment prefill fails, release that queued input prefill as well;
   errors remain silent as in the current background-prefill behavior.
6. If there is no fragment, skip steps 2-5 and keep the existing behavior.

## Invariants

- All prefill and turn operations retain the same session ID and llama.cpp slot.
- The fragment prefill precedes any later user-input prefill, so it remains a
  prefix of the later cached prompt.
- Debouncing continues to coalesce input to the latest revision; no stale
  user-input prefill is released after fragment warmup.
- The fragment remains hidden from the ordinary transcript.

## Verification

Add regression coverage for a completed fragment-bearing turn that queues a
later user action: prove the fragment prefill is submitted first and that the
latest queued input is submitted only after it completes. Also cover the
no-fragment path to prove its current immediate/debounced behavior is unchanged,
and the failure path to prove queued input is released.
