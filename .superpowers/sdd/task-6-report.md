# Task 6 Report: llama.cpp end-to-end documentation and verification

## Status

Updated the README with the llama.cpp startup and connection flow, then
completed the required automated verification. No production source files were
changed.

## Documentation

- Documents the exact loopback startup command:

  ```bash
  llama-server --host 127.0.0.1 --port 8081 --model /absolute/path/to/model.gguf
  ```

- Corrects application startup to use `./gradlew :server:run` and
  `./gradlew :web:browserDevelopmentRun` in separate terminals, with the
  browser client at `http://127.0.0.1:8088/`.
- Explains that the first **Check llama.cpp** click discovers a model and
  disables the port, while the second, model-named button click creates and
  starts the session.
- Explains that reopening a stored llama.cpp session returns to the detail view
  with its stored port editable before reconnecting.

## Automated verification

```text
./gradlew check
BUILD SUCCESSFUL in 10s
27 actionable tasks: 3 executed, 24 up-to-date
```

The captured output shows the server JVM test task, Kotlin/JS test compilation,
browser test task, and production browser webpack task completing successfully.
It also shows the existing Kotlin `kotlin-js` deprecation warning and webpack
asset-size warnings; no test failure occurred.

## Local runtime verification

Started the application processes only for this verification:

```text
./gradlew :server:run
curl -fsS http://127.0.0.1:8080/api/health
{"status":"ok"}

./gradlew :web:browserDevelopmentRun
curl -fsSI http://127.0.0.1:8088/
HTTP/1.1 200 OK
```

No `llama-server` was available on `127.0.0.1:8081`: a direct
`GET /v1/models` probe was refused and no listener existed on that port. The
conditional `GET /api/llama/models?port=8081` discovery check and the
llama.cpp-dependent browser flow therefore could not be run. This is an
environmental limitation, not a code or test failure.

## Final audit

```text
git status --short
# empty output

git diff --check
# no output

ss -ltnpe | rg ':8080|:8081|:8088' || true
# no output
```

Both verification servers were stopped with Ctrl-C. `./gradlew --stop` then
stopped the Gradle daemon started during verification. No agent-started listener
remains on ports 8080, 8081, or 8088.

## Concern

The only unperformed validation is the real llama.cpp discovery, two-click
session start, action streaming, and editable-port resume sequence. Run it once
with a local `llama-server` on `127.0.0.1:8081`; the README now provides the
exact startup and browser steps.

## Final review follow-up (2026-07-15)

Added regressions for whitespace/newline llama.cpp SSE fragments and stale
llama.cpp discovery after navigation. The current change also updates the
opening README description for both local providers.

Available verification:

```text
./gradlew :web:browserTest --tests de.aidadventure.web.LlamaCppConnectionStateTest --console=plain
BUILD SUCCESSFUL in 7s
18 actionable tasks: 1 executed, 17 up-to-date
```

The whitespace regression was red before removing the production
`isNotBlank()` filter. Its first green run exposed a test-fixture escape issue
(`"\\n"` decoded as a literal backslash and `n`); the fixture was corrected to
the JSON newline escape. The immediate confirmation command was deliberately
interrupted at the user's request to stop stalled Gradle work, so no final
server-test, `./gradlew check`, or production-webpack success is claimed for
this follow-up.
