# Primary World-State Resolution

## Goal

Remove the Secondary-LLM process and resolve `WorldState` through the Primary
llama.cpp server as a serialized post-turn operation in the existing session
coordinator.

## Design

After a visible Primary turn finishes, its retained llama.cpp slot remains
owned by the session coordinator. The coordinator runs the bounded structured
World-State analysis on that same slot, validates the result, and immediately
runs the existing canonical warmup with the accepted state. The operation is
serialized with prefill and turn requests, so a newer prefill is queued rather
than racing the analysis or warmup. The analysis prompt is built as a
continuation of the completed Primary turn and uses `cache_prompt=true`.

The HTTP handler reports `input_ready` after the visible stream, then waits for
the coordinator's World-State operation before sending `done`. The browser
therefore receives the same accepted state that was used for the next warmup.
Transport, schema, and validation failures retain the previous state and still
allow the canonical warmup to complete.

The Secondary port, model variables, launcher process, and documentation are
removed. The Primary launcher starts one llama.cpp process, and the JVM only
needs `--llama-port`.

## Verification

Tests cover same-slot analysis followed by warmup, queueing a prefill during
analysis, preservation of the old state after invalid analysis, and the
existing new-game/resume/following-turn cache contract. Launcher tests verify
that only Primary model configuration is required and only one model process
is started.
