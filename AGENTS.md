# Agent Guide

## Commands

- `./gradlew check` — compile and test both modules.
- `./gradlew :server:run` — start the API on port 8080.
- `./gradlew :web:jsBrowserDevelopmentRun` — start the browser client.

## Invariants

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Treat every LLM response as untrusted text.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
