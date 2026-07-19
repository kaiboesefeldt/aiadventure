# TRACE LLM Logging Design

## Goal

Make all server-to-llama.cpp communication inspectable at TRACE level while
keeping the normal server log at INFO by default. A command-line option must
select the global log filter. Streamed model output must be written once, only
after the final response is complete.

## Scope

The server adds `--log-level=<TRACE|DEBUG|INFO|WARN|ERROR>`; omitting it keeps
the existing effective default of `INFO`. The option configures SLF4J Simple
before the Ktor server and its loggers are created.

TRACE logging is deliberately content-bearing. It records the semantic prompt
messages sent to llama.cpp and each final non-empty completion. Existing
operational error logs remain content-free and retain their request-ID-only
format.

## Trace Transcript

`LlamaCppGateway` owns a concurrent, in-memory transcript cursor keyed by the
LLM session ID. For each prompt it compares the current message history with
the last logged history for that session:

1. The first prompt logs its complete message sequence.
2. A later prompt logs only the suffix from the first changed history entry,
   plus any current action or auxiliary analysis message not already logged.
3. If the current history is identical to the prior logged history, it logs no
   history entries. If an earlier entry was edited or the history was shortened,
   it logs from that first differing position so the trace remains truthful.
4. A session-specific cursor advances only after its prompt was emitted. It is
   process-local diagnostic state; restarting the server begins a new trace.

The trace record uses message roles and escaped multi-line content, rather than
the opaque `/apply-template` result. This makes history deduplication visible
and preserves the exact semantic prompt order: instruction, original scenario,
history, new action. The existing fingerprint, slot, port, and cache metrics
remain metadata only.

## Completion Logging

Non-streaming calls log their received completion text once at TRACE. For the
native `/completion` stream, the gateway appends content chunks internally and
emits one final TRACE response record when llama.cpp sends its terminal chunk.
It never logs individual streaming tokens. The alternative chat-completion
stream follows the same accumulation rule and logs at `[DONE]`.

Prefill and warmup have no generated text and therefore log their prompt and
completion metrics but no fabricated response body. Fragment scoring logs its
one-token final result.

## Failure Handling and Constraints

LLM text remains untrusted and is only rendered as escaped log content; it is
never interpreted as log syntax. TRACE content logging is opt-in through the
explicit filter setting. The deterministic prompt-prefix and llama.cpp cache
contracts are unchanged: requests continue to use the same session ID and
slot, and diagnostics must not change the HTTP payloads or request ordering.

## Verification

Tests cover log-level parsing and INFO default. Gateway tests capture TRACE
output to prove that a first prompt emits the full semantic history, a follow-up
emits only the changed suffix, and a rewritten history restarts at the first
change. Stream tests prove the final answer appears exactly once and no
individual token record is produced. The final verification is `./gradlew
check`.
