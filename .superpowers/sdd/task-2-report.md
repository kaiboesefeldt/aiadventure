# Task 2 Report: llama.cpp discovery and streaming gateway

## Scope completed

- Added `LlamaCppGateway` using only `http://localhost:<port>/v1/models` and `http://localhost:<port>/v1/chat/completions`.
- Model discovery decodes OpenAI-compatible `data[].id`, rejects non-2xx responses, and rejects an empty model list.
- Turn streaming calls `PromptFactory.build(request)`, maps its system/user/assistant messages to OpenAI roles without changing their order, and sends `model`, `messages`, and `stream: true`.
- SSE parsing ignores non-`data:` lines, forwards nonblank content deltas as token events, emits one null-metrics done event for `[DONE]`, and closes with an exception for HTTP, JSON, or error-object failures.
- `OllamaGateway` and `LangChain4jOllamaGateway` were intentionally not changed: Task 3 injects `LlamaCppGateway` separately and performs provider dispatch.

## Test-first evidence

1. Added the local `HttpServer` contract test before production code.
2. Red run: `:server:compileTestKotlin` with Kotlin/JS resource exclusions failed solely on the missing `LlamaCppGateway` type.
3. First green implementation run exposed the missing `Done` event after `[DONE]`; the existing test caught it.
4. Added the terminal event and reran the focused gateway test successfully.

## Verification

- Passed (fresh execution): `./gradlew --no-daemon --rerun-tasks :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources`
- Result: `BUILD SUCCESSFUL in 11s`; 4 actionable tasks executed; the local `HttpServer` gateway test passed.
- The unexcluded server test command stalled in root `kotlinNpmInstall`.
- A full server run with the required exclusions reached 28 tests, but two existing packaged-browser-asset tests failed because `:server:processResources` was intentionally skipped and therefore did not package the browser resources. This does not exercise the gateway code.
- `git diff --check` passed before staging.

## Runtime cleanup

- All Gradle invocations used `--no-daemon` after the initial root-task attempt.
- No agent-started Gradle, Yarn, Node, HTTP-server, or application process remained after verification.

## Review fixes (2026-07-14)

- EOF before the required `data: [DONE]` frame now closes the llama.cpp flow with `IllegalStateException("llama.cpp chat completion ended before [DONE].")` rather than a normal completion.
- The synchronous `HttpClient.send` call and line-by-line response read now run in a `Dispatchers.IO` child. Its response body is held for `awaitClose`, which closes it and cancels the child when a downstream collector cancels.
- The connect phase additionally uses `runInterruptible`, so cancellation before response headers interrupts the synchronous `HttpClient.send` call instead of waiting for the upstream server.
- Added local `HttpServer` regression coverage for both an EOF-before-`[DONE]` stream and cancelling a collector while the upstream response remains open.

### Test-first evidence

1. EOF regression red run:

   ```text
   ./gradlew --no-daemon --rerun-tasks :server:test --tests 'de.aidadventure.server.llamacpp.LlamaCppGatewayTest.fails when the upstream stream ends before DONE' -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources
   LlamaCppGatewayTest > fails when the upstream stream ends before DONE FAILED
       java.lang.AssertionError at LlamaCppGatewayTest.kt:29
   1 test completed, 1 failed
   BUILD FAILED in 11s
   ```

2. Both new regressions red run:

   ```text
   ./gradlew --no-daemon --rerun-tasks :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources
   LlamaCppGatewayTest > fails when the upstream stream ends before DONE FAILED
       java.lang.AssertionError at LlamaCppGatewayTest.kt:41
   LlamaCppGatewayTest > cancelling the collector stops an open upstream response FAILED
       kotlinx.coroutines.TimeoutCancellationException at CoroutineDebugging.kt:42
   3 tests completed, 2 failed
   BUILD FAILED in 13s
   ```

3. Pre-response cancellation regression red run:

   ```text
   ./gradlew --no-daemon --rerun-tasks :server:test --tests 'de.aidadventure.server.llamacpp.LlamaCppGatewayTest.cancelling the collector interrupts an upstream request before it responds' -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources
   LlamaCppGatewayTest > cancelling the collector interrupts an upstream request before it responds FAILED
       kotlinx.coroutines.TimeoutCancellationException at CoroutineDebugging.kt:42
   1 test completed, 1 failed
   BUILD FAILED in 13s
   ```

### Verification

1. Focused gateway suite:

   ```text
   ./gradlew --no-daemon --rerun-tasks :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources
   BUILD SUCCESSFUL in 11s
   4 actionable tasks: 4 executed
   ```

3. Focused gateway suite after the pre-response cancellation fix:

   ```text
   ./gradlew --no-daemon --rerun-tasks :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources
   BUILD SUCCESSFUL in 11s
   4 actionable tasks: 4 executed
   ```

2. Required unmodified full-server gate (bounded attempt):

   ```text
   ./gradlew :server:test
   > Task :kotlinNpmInstall
   warning Waiting for the other yarn instance to finish (552773)
   ```

   The command remained at the known root Kotlin/JS/Yarn contention point and was interrupted rather than changing production code or tests to bypass it. Exit code: `130`.

## Final review fix (2026-07-15)

- Replaced nullable response-body publication with a one-way owner state (`Awaiting`, `Published`, `Closed`). A body returned after collector cancellation is closed during publication and is never read.
- Published the body inside the interruptible send operation, so cancellation immediately after `HttpClient.send` returns cannot strand an unowned body.
- Removed `useLines`; the owner is the sole closer, and both `awaitClose` and reader completion release it through the same idempotent transition.
- Added a deterministic cancellation/publication race regression, plus local `HttpServer` tests for a non-2xx provider response and an SSE JSON error object.

### Test-first evidence

1. Race regression red run:

   ```text
   ./gradlew --no-daemon --rerun-tasks :server:test --tests 'de.aidadventure.server.llamacpp.LlamaCppGatewayTest.cancelling before response publication closes the later body without reading it' -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources
   LlamaCppGatewayTest > cancelling before response publication closes the later body without reading it FAILED
       java.lang.AssertionError at LlamaCppGatewayTest.kt:54
   1 test completed, 1 failed
   BUILD FAILED in 10s
   ```

2. Race regression green run:

   ```text
   ./gradlew --no-daemon --rerun-tasks :server:test --tests 'de.aidadventure.server.llamacpp.LlamaCppGatewayTest.cancelling before response publication closes the later body without reading it' -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources
   BUILD SUCCESSFUL in 10s
   4 actionable tasks: 4 executed
   ```

3. Focused gateway suite green run:

   ```text
   ./gradlew --no-daemon --rerun-tasks :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest -x kotlinNpmInstall -x kotlinStoreYarnLock -x :server:processResources
   BUILD SUCCESSFUL in 11s
   4 actionable tasks: 4 executed
   ```
