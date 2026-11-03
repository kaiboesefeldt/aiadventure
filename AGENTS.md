# Agent Guide

## Commands

- `./gradlew check` — compile and test both modules.
- `./gradlew :server:run` — start the loopback-only API on port 8080; use `ADVENTURE_EXPOSE_LAN=true` only to opt into LAN exposure.
- `curl -fsS http://localhost:8080/api/health` — verify the API is reachable.
- `curl -fsS 'http://localhost:8080/api/models?port=8081'` — list models exposed by local llama.cpp.

## Build hygiene

- Use `./gradlew` for Kotlin/JS work; it exports a project-local npm cache for
  this checkout/worktree.
- Serialize Gradle commands that can install Kotlin/JS dependencies per
  checkout. After interrupting one, check for its child installer before retrying.
- Final verification is `./gradlew check` without excluding dependency-install tasks.

## Development workflow

- Make code changes in a dedicated branch and Git worktree, separate from the primary checkout.
- Implement code changes with subagents.
- At the end of a code-change task, merge the task branch into `main`, then delete the task branch and remove its worktree.

## Invariants

- Keep the game-master prompt prefix deterministic: instruction, original scenario, history, new action.
- Prompt caching is a release-blocking playability requirement. Any change to prompt construction, warmup/prefill/turn flow, coordinator lifecycle, leases, or slots must preserve the local cache-contract tests for new games, resumed games, and following turns. Those tests require both an exact prompt-prefix relationship and one retained warm llama.cpp slot; do not weaken or remove them without an equivalent proof.
- Treat every LLM response as untrusted text.
- Browser IndexedDB owns scenarios and sessions; the server remains stateless.
- A prefill and its turn must use the same `sessionId`. Cache metric semantics
  depend on the llama.cpp version; use verified reuse and evaluation counts as
  described in the README and do not infer reuse from elapsed time alone.
- Log operational gateway/model failures with request IDs only. The explicitly local,
  loopback-only `TRACE` diagnostic mode is the exception: it may log scenario,
  history, actions, and complete LLM responses, including structured World-State
  completions.
