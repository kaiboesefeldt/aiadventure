# Task 4 Report: Ktor llama.cpp prefill API and Ollama removal

## Implementation

- Replaced provider-specific model discovery with `GET /api/models?port=<llama.cpp port>`.
- Added `POST /api/prefill`, which validates the live session identity and non-negative revision, submits to the keyed coordinator, and returns `202 Accepted` with `PrefillAccepted`.
- Routed `POST /api/turn` through the same `(sessionId, modelId, port)` coordinator after llama.cpp model validation; SSE remains the only turn-output path.
- Made server turn/prefill DTOs llama.cpp-only, including a required session ID and port.
- Removed the Ollama gateway, its dependency, Koin wiring, provider branches, model catalog, and Ollama-only tests.
- Retained the direct `langchain4j` dependency because `PromptFactory` and the native llama.cpp template gateway use its message types; the removed `langchain4j-ollama` dependency is no longer present.
- Stabilized the existing registry TTL test by waiting for the coordinator's actual release condition rather than racing its asynchronous prefill-completion callback.

## Strict TDD evidence

RED:

```text
./gradlew :server:test --tests de.aidadventure.server.ApplicationTest.prefill accepts without waiting for evaluation
BUILD FAILED
```

The new endpoint test received the absent-route result instead of `202 Accepted`.

GREEN:

```text
./gradlew :server:test --tests de.aidadventure.server.ApplicationTest
BUILD SUCCESSFUL
```

The focused route suite covers llama.cpp discovery, missing-port rejection, prompt prefill acceptance, invalid identity/revision rejection without registry access, coordinated SSE turns, and unknown-model rejection.

## Verification

The first aggregate server run exposed a timing race in the pre-existing Task 3 TTL test: its gateway completion signal preceded the coordinator's state transition. The test now conditionally retries TTL cleanup until the observed release condition, with a bounded timeout; no production concurrency behavior changed.

```text
./gradlew check
BUILD SUCCESSFUL in 10s
27 actionable tasks: 2 executed, 25 up-to-date
```

`rg -n -i 'ollama|ModelProvider' server --glob '!build/**'` produced no matches, and `git diff --check` produced no whitespace errors.
