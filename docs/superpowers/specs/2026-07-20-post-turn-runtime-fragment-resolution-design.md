# Post-turn Runtime Fragment Resolution Design

The Lucene runtime-fragment resolver runs exactly once after a completed LLM
turn, with the completed visible history: prior history, the player action,
and the generated game-master response. `/api/prefill` and the current
`/api/turn` never invoke it.

The post-turn coordinator reservation retains its llama.cpp slot while the
candidate is scored and the canonical history is warmed. Following prefill
requests queue and direct turns wait. An accepted fragment is retained by the
coordinator and added to the next matching prefill or turn; a rejected or
absent candidate adds nothing. This preserves prompt-prefix and same-slot
cache contracts without logging private game content.

Verification covers no route-level resolution, exactly one completed-turn
query, accepted-fragment propagation, waiting-turn handoff, focused server
tests, and `./gradlew check`.
