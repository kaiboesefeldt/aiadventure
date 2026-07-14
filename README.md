# AI Adventure

A local, Ollama-backed interactive adventure application. The JVM server provides the API; the Kotlin/JS React client runs in the browser.

## Prerequisites

- Java (a JVM capable of producing Java 21 bytecode)
- [Ollama](https://ollama.com/) with a local model

## Start

Start Ollama and ensure a model is available:

```bash
ollama serve
ollama pull example-model
```

In separate terminals, start the API and browser client:

```bash
./gradlew :server:run
./gradlew :web:browserDevelopmentRun
```

The API listens on port 8080. The browser development server prints its local URL when ready.

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

With the API running, check that it can reach the local Ollama installation:

```bash
curl -fsS http://localhost:8080/api/health
curl -fsS http://localhost:8080/api/models
```

The first command returns `{"status":"ok"}`. The second returns the locally
installed model IDs; choose one of those IDs in the browser.

Start the browser client with `./gradlew :web:browserDevelopmentRun`, open the
local URL printed by Gradle, import `harbor.md`, open **Fog Harbor**, choose a
model, and start a session. Send a short action, reload the page, reopen the
scenario, resume the saved session, then send a second short action without
changing the scenario or model. Scenarios and sessions are stored in this
browser's IndexedDB, so they persist across a reload of the same browser
profile; the server has no session storage.

For a request-level check, use the browser's Network panel: both `POST
/api/turn` requests contain the same `scenarioMarkdown` and `modelId`; the
second request's `history` contains the first completed turn. The server builds
each game-master prompt in a fixed order: instruction, original scenario,
history, then the new player action.

### Metrics and cache limitation

The UI displays whichever optional metrics the server supplies. The current
LangChain4j/Ollama gateway exposes input-token usage when available, but does
not receive total-duration or prompt-cache-token values from that integration;
those fields therefore remain `null` in the API response and are not displayed
by the UI. Do not infer a prompt-cache hit from the presence of a second turn.
Verifying cache-token reuse requires an Ollama adapter that exposes cache
metrics (or separate Ollama request instrumentation).
