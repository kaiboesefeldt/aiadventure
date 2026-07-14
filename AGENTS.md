# Agent Guide

## Commands

- `./gradlew check` — compile and test both modules.
- `./gradlew :server:run` — start the loopback-only API on port 8080; use `ADVENTURE_EXPOSE_LAN=true` only to opt into LAN exposure.
- `./gradlew :web:browserDevelopmentRun` — start the browser client on `http://localhost:8088`.
- `curl -fsS http://localhost:8080/api/health` — verify the API is reachable.
- `curl -fsS http://localhost:8080/api/models` — list models exposed by local Ollama.

## Invariants

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Treat every LLM response as untrusted text.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
- Do not claim prompt-cache metrics unless the active Ollama integration actually returns them.
- Log operational gateway/model failures with request IDs only; never log scenario, history, actions, or LLM text.
