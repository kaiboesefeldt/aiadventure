# Agent Guide

## Commands

- `./gradlew check` — compile and test both modules.
- `./gradlew :server:run` — start the loopback-only API on port 8080; use `ADVENTURE_EXPOSE_LAN=true` only to opt into LAN exposure.
- `curl -fsS http://localhost:8080/api/health` — verify the API is reachable.
- `curl -fsS 'http://localhost:8080/api/models?port=8081'` — list models exposed by local llama.cpp.

## Invariants

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Treat every LLM response as untrusted text.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
- A prefill and its turn must use the same `sessionId`; only llama.cpp's returned
  `tokens_cached` and `tokens_evaluated` metrics prove cache reuse.
- Log operational gateway/model failures with request IDs only; never log scenario, history, actions, or LLM text.
