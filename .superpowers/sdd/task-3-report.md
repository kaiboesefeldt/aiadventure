# Task 3 Report: llama.cpp discovery and provider-aware turns

## Status

The requested implementation was already present in this worktree before this execution, in commit `cbfe798 feat(server): expose llama.cpp provider routes`. Subsequent llama.cpp reliability commits were preserved unchanged. No source-code rewrite was made because the implementation and its regression coverage satisfy the brief as checked below.

## Implementation audited

- `Application.adventureModule` accepts an injectable `LlamaCppGateway`, defaults to a local HTTP-backed gateway, and exposes it through Koin alongside the existing `OllamaGateway`.
- `GET /api/llama/models?port=<integer>` parses ports through `LlamaCppPort`, returns `ModelsResponse`, produces `400 invalid_request` for blank, non-numeric, zero, and out-of-range ports, and produces `503 llama_cpp_unavailable` if discovery fails.
- `POST /api/turn` selects Ollama or llama.cpp from `TurnRequest.provider`. The llama.cpp path requires a valid `llamaCppPort`, validates `modelId` against that port's discovery result, then streams through the llama.cpp gateway. Missing models still return `404 model_not_found`.
- The existing Ollama `ModelCatalog` cache remains the only catalog cache and is still used for both `/api/models` and Ollama turns. The deterministic prompt factory was not changed.
- SSE frame names and safe provider-specific unavailable errors are preserved. Operational logging uses request IDs, operation/result labels, and exception class names only; it does not log scenario, history, action, or model output.

## TDD evidence

The feature's route and dispatch tests are already part of `cbfe798`, including success, invalid-port, unavailable-discovery, provider dispatch, provider stream failure, and Ollama-catalog-cache cases. Because that committed implementation was already present at the start of this run, no honest red-phase run was possible without temporarily breaking existing code; no artificial test/code churn was introduced. The current green verification is recorded below.

## Verification

Passed focused application suite:

```text
./gradlew :server:test --tests de.aidadventure.server.ApplicationTest \
  -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources

BUILD SUCCESSFUL in 2s
```

Passed all server tests and whitespace validation:

```text
./gradlew :server:test \
  -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources \
  && git diff --check

BUILD SUCCESSFUL in 3s
```

The generated JUnit results contain 40 tests across the server module, including 21 `ApplicationTest` cases, with zero failures, errors, or skips.

## Packaged-resource note

`server:processResources` was intentionally excluded as requested. The application tests that exercise `/` and `/web.js` still passed against the existing test runtime resources, but this run does not freshly verify resource packaging. No packaged-resource failure occurred in the executed commands; fresh packaging verification remains intentionally out of scope for this task's required command line.

## Process cleanup

No long-running server or browser process was started. Test-local HTTP servers close through their test scopes. A final listener audit found no listeners on ports 8080, 8081, or 8088.

## Task 3 review follow-up (2026-07-15)

### Review findings fixed

- `GET /api/llama/models` now maps `java.io.IOException` from llama.cpp discovery (including a refused loopback connection) to the existing client-safe `503 llama_cpp_unavailable` response.
- `POST /api/turn` maps the same discovery failure during llama.cpp model validation to `503 llama_cpp_unavailable` before attempting to open an SSE stream.
- Logs still contain only request ID, operation, result, and exception class; no response body or request content is logged.
- `ApplicationTest` covers refused discovery for both routes and verifies an unknown llama.cpp model returns `404` with zero provider stream calls.

### TDD evidence

Red phase:

```text
./gradlew :server:test --tests de.aidadventure.server.ApplicationTest \
  -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources --console=plain

24 tests completed, 2 failed
ApplicationTest > llama turn maps a refused model discovery connection to service unavailable FAILED
ApplicationTest > llama models maps a refused connection to service unavailable FAILED
BUILD FAILED
```

Green phase:

```text
./gradlew :server:test --tests de.aidadventure.server.ApplicationTest \
  -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources --console=plain \
  && git diff --check

BUILD SUCCESSFUL in 3s
```

Full server verification:

```text
./gradlew :server:test \
  -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources --console=plain \
  && git diff --check

BUILD SUCCESSFUL in 3s
```

The generated JUnit XML reports 43 server tests with zero failures, errors, or skips, including all 24 `ApplicationTest` cases.
