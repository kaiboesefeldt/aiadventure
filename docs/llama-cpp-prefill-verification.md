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
3. Use the disposable scenario below. Do not copy its content into logs,
   screenshots, or issue reports.

## Disposable template

Create and import this exact disposable template. It exercises the feature
without placing real scenario material in the evidence record:

```markdown
---
runtime-fragments:
  "The brass compass points toward the old observatory": "A sealed maintenance passage is relevant to the next decision."
---
# Verification Harbor

Explore a quiet harbor and respond briefly to the player's actions.
```

Do not copy the template, player actions, prompts, history, model output, or
model identifier into terminal logs, screenshots, issue reports, or evidence.

## Acceptance checklist

Perform these steps in one browser profile. Keep the same session selected
through reload/resume; do not create a replacement session.

1. Start a new session and complete a first short turn. Confirm that no
   `fragment` SSE event is sent and no `runtime-fragment` entry exists in the
   saved transcript. This proves the first turn has no fragment.
2. Complete a second turn whose preceding visible player/game-master pair
   matches the disposable template's retrieval text. Confirm the turn stream
   sends its `fragment` event before token events, and that the saved history
   contains exactly one `runtime-fragment` entry immediately before the second
   player entry.
3. Confirm that the selected fragment never appears in the visible transcript
   before, during, or after completion. The player-facing UI must show only
   player and game-master messages.
4. Reload the page, reopen the imported template, and resume that same saved
   session. Confirm the hidden entry remains persisted but remains invisible.
5. Type a third short action, wait for the debounced `POST /api/prefill` to
   return `202 Accepted`, then send that exact action. Confirm prefill and
   turn use the same `sessionId`, then complete the following turn.
6. While the session remains live, inspect `GET http://127.0.0.1:8081/slots`
   before and after the prefill/turn handoff. Confirm the same retained slot
   identity owns the session through the following turn. Do not include any
   other slot metadata in evidence.
7. For each completed turn, collect backend `timings.cache_n` and
   `timings.prompt_n`, when reported, as the reused and newly evaluated prompt
   token counts. Confirm their semantics for the llama.cpp version under test.
   Top-level `tokens_cached` and `tokens_evaluated` may represent final cache
   occupancy and total prompt length instead, so do not treat those fields
   alone as proof of reuse. If the timing counts are absent, reuse is unknown.
   Duration, a warm model, or matching session IDs alone are not proof.

Use a content-free evidence table:

| Stage | Request ID | Slot identity | timings.cache_n | timings.prompt_n |
| --- | --- | --- | --- | --- |
| first turn, no fragment | | | | |
| selected later fragment | | | | |
| reload/resume following turn | | | | |

Document the llama.cpp version and the meanings of the returned counters with
the evidence. Duration, an apparent warm model, or a subsequent turn are not
proof of reuse.

## Evidence boundary

Never capture or log scenario text, player actions, prompt text, conversation
history, model output, model identifiers, or other slot metadata. Evidence
contains only request IDs, the retained slot identity, and aggregate
cache/evaluated-token counters.
