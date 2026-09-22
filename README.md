# AI Adventure

A local-LLM interactive adventure application using a local
[llama.cpp](https://github.com/ggml-org/llama.cpp) server. The JVM server
provides the API and serves the Kotlin/JS React client.

## Prerequisites

- Java (a JVM capable of producing Java 21 bytecode)
- A local `llama-server` from [llama.cpp](https://github.com/ggml-org/llama.cpp)

## Start

Create a local configuration, set `PRIMARY_MODEL_PATH`, `SECONDARY_MODEL_PATH` and, if necessary, the
remaining values, then start both local processes:

```bash
cp .env.example .env
# Edit .env to set PRIMARY_MODEL_PATH and, if needed, the remaining values.
./server.sh
```

`server.sh` starts primary and secondary local llama.cpp servers. The primary owns cache slots; on
Ctrl-C or `TERM`, it terminates both llama.cpp and application children.

Open [http://127.0.0.1:8080/](http://127.0.0.1:8080/) in the browser.

The server listens only on `127.0.0.1:8080` by default.
Set `PORT` in `.env` to use another port (for example, `PORT=8088`), then open
that port in the browser. API requests use the same origin as the page.
Set `ADVENTURE_EXPOSE_LAN=true` only when you intentionally need the API reachable on
your LAN. Do not use that setting on an untrusted network.

### TRACE diagnostics

`LOG_LEVEL=TRACE` is an opt-in local diagnostic mode. It deliberately records
scenario, action, prompt and complete LLM-response content, including the raw
Secondary `/completion` response used for WorldState resolution, so cache and
model behavior can be inspected. Do not enable it when those local logs may be
exposed.

### Connect to llama.cpp

Import or open a scenario, then start a new session or resume a saved one. The
server owns the localhost llama.cpp connection and its single model; scenarios
and sessions remain in this browser profile.

While typing an action, the browser silently sends a debounced `POST /api/prefill`
for the current session. The server responds with `202 Accepted`; this does not add a
transcript entry or block sending. Sending the action uses `POST /api/turn` with the
same `sessionId`, allowing llama.cpp to reuse that session's cache. `--parallel 1`
is unnecessary: each active session reserves its own slot, and a full server returns
a safe busy error rather than using another session's cache. The launcher configures
llama.cpp with a writable temporary slot directory so it can clear released slots.

## Test-Spiel laden

Wenn du nur ein direkt ladbares Beispiel willst, importiere diese Datei in der
Browser-Oberfläche:

```text
examples/test-game.md
```

Das Szenario heißt "Test Spiel: Der Leuchtturm von Nebelhafen" und ist so
geschrieben, dass du es sofort als kleines Smoke-Test-Spiel starten kannst.

## Verify

```bash
./gradlew check
```

The command compiles both modules and runs their test suites.

### Local smoke test

Create a scenario file named `harbor.md`:

```markdown
# Fog Harbor
You are investigating a missing lighthouse keeper.
```

With the API running, check that it can reach local llama.cpp:

```bash
curl -fsS http://localhost:8080/api/health
```

The command returns `{"status":"ok"}`.

A successful `POST /api/turn` is an SSE response: it sends one or more `token`
frames followed by a terminal `done` frame:

```bash
curl -N -X POST http://localhost:8080/api/turn \
  -H 'Content-Type: application/json' \
  --data '{"sessionId":"manual-check","scenarioMarkdown":"# Fog Harbor","history":[],"playerAction":"look"}'
```

With `./gradlew :server:run` running, open http://127.0.0.1:8080/, import
`harbor.md`, open **Fog Harbor**, and start a session. Send a short action,
reload the page, reopen the scenario, resume the saved session, then send a
second short action without changing the scenario.
Scenarios and sessions are stored in this browser's IndexedDB, so they persist
across a reload of the same browser profile; the server has no session storage.

For a request-level check, use the browser's Network panel: both `POST
/api/turn` requests contain the same `scenarioMarkdown`; the second request's
`history` contains the first completed turn. The server builds
each game-master prompt in a fixed order: instruction, original scenario,
history, then the new player action.

### Cache metrics

The UI displays optional duration, prompt-token, and cache-token metrics. Only
the backend values `tokens_cached` and `tokens_evaluated` prove cache reuse;
do not infer a cache hit from timing or from a second turn alone. See
[`docs/llama-cpp-prefill-verification.md`](docs/llama-cpp-prefill-verification.md)
for the privacy-safe live verification checklist. That checklist uses a
disposable frontmatter template and covers the first turn (no fragment), a
later hidden fragment, reload/resume, and a following cache-reusing turn. Its
evidence record contains only request IDs, the retained slot identity, and
`tokens_cached` / `tokens_evaluated`.
