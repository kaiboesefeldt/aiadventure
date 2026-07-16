# AI Adventure

A local-LLM interactive adventure application using a local
[llama.cpp](https://github.com/ggml-org/llama.cpp) server. The JVM server
provides the API and serves the Kotlin/JS React client.

## Prerequisites

- Java (a JVM capable of producing Java 21 bytecode)
- A local `llama-server` from [llama.cpp](https://github.com/ggml-org/llama.cpp)

## Start

Start llama.cpp's OpenAI-compatible server on loopback port `8081` with at
least two cache slots:

```bash
llama-server --host 127.0.0.1 --port 8081 --model /absolute/path/to/model.gguf --parallel 4 --slots --slot-save-path /tmp/ai-adventure-slots
```

Then start the application:

```bash
./gradlew :server:run
```

Open [http://127.0.0.1:8080/](http://127.0.0.1:8080/) in the browser. Stop the
application with Ctrl-C in its Gradle terminal.

The server listens only on `127.0.0.1:8080` by default.
Set `ADVENTURE_EXPOSE_LAN=true` only when you intentionally need the API reachable on
your LAN. Do not use that setting on an untrusted network.

### Connect to llama.cpp

Import or open a scenario, enter the llama.cpp port (normally `8081`), select a
discovered model, and start a session. Scenarios and sessions remain in this
browser profile. Reopening a session verifies its saved llama.cpp connection before
resuming.

While typing an action, the browser silently sends a debounced `POST /api/prefill`
for the current session. The server responds with `202 Accepted`; this does not add a
transcript entry or block sending. Sending the action uses `POST /api/turn` with the
same `sessionId`, allowing llama.cpp to reuse that session's cache. `--parallel 1`
is unnecessary: each active session reserves its own slot, and a full server returns
a safe busy error rather than using another session's cache. `--slot-save-path` is
required so the server supports clearing a released slot; use a directory writable by
the llama.cpp process.

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
curl -fsS 'http://localhost:8080/api/models?port=8081'
```

The first command returns `{"status":"ok"}`. The second returns the locally
installed model IDs; choose one of those IDs in the browser.

A successful `POST /api/turn` is an SSE response: it sends one or more `token`
frames followed by a terminal `done` frame. Check the stream directly with a
model ID returned by `/api/models?port=8081`:

```bash
curl -N -X POST http://localhost:8080/api/turn \
  -H 'Content-Type: application/json' \
  --data '{"sessionId":"manual-check","modelId":"YOUR_MODEL","llamaCppPort":8081,"scenarioMarkdown":"# Fog Harbor","history":[],"playerAction":"look"}'
```

With `./gradlew :server:run` running, open http://127.0.0.1:8080/, import
`harbor.md`, open **Fog Harbor**, choose a model, and start a session. Send a
short action, reload the page, reopen the scenario, resume the saved session,
then send a second short action without changing the scenario or model.
Scenarios and sessions are stored in this browser's IndexedDB, so they persist
across a reload of the same browser profile; the server has no session storage.

For a request-level check, use the browser's Network panel: both `POST
/api/turn` requests contain the same `scenarioMarkdown` and `modelId`; the
second request's `history` contains the first completed turn. The server builds
each game-master prompt in a fixed order: instruction, original scenario,
history, then the new player action.

### Cache metrics

The UI displays optional duration, prompt-token, and cache-token metrics. Only
the backend values `tokens_cached` and `tokens_evaluated` prove cache reuse;
do not infer a cache hit from timing or from a second turn alone. See
[`docs/llama-cpp-prefill-verification.md`](docs/llama-cpp-prefill-verification.md)
for the privacy-safe live verification checklist.
