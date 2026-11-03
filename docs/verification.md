# Verification and troubleshooting

## Verify

```bash
./gradlew check
```

The command compiles both modules and runs their test suites.
Browser tests use headless Chromium; install Chromium or Chrome and set
`CHROME_BIN` if it is not found automatically. The build downloads its Gradle,
Kotlin, Node.js and npm dependencies on first use.

Run `bash scripts/test-server-launcher.sh` for isolated launcher regression tests
(requires Python 3; does not start actual models or modify your `.env`).

The repository includes a Woodpecker CI configuration for Codeberg. It requires
the repository to be enabled in a Woodpecker instance; configuration alone does
not establish that a hosted run has occurred.

### Troubleshooting

- If `./server.sh` cannot find the model, check `PRIMARY_MODEL_PATH` and file
  permissions in `.env`; if using a wrapper, check that it is executable.
- If the page cannot connect, check the application health endpoint below and
  confirm that the configured port matches the browser URL. A successful health
  response does not establish that the model server is available.
- If a saved game exceeds the model context, use a larger configured llama.cpp
  context. The application reports a limit rather than silently dropping history.

### Local smoke test

Create a scenario file named `harbor.md`:

```markdown
# Fog Harbor
You are investigating a missing lighthouse keeper.
```

With the application server running, check that its HTTP endpoint is reachable:

```bash
curl -fsS http://localhost:8080/api/health
```

The command returns `{"status":"ok"}`. This health endpoint confirms that the
application server responds; it does not verify llama.cpp connectivity.

A successful `POST /api/turn` is an SSE response: it sends one or more `token`
frames followed by a terminal `done` frame:

```bash
curl -N -X POST http://localhost:8080/api/turn \
  -H 'Content-Type: application/json' \
  --data '{"sessionId":"manual-check","scenarioMarkdown":"# Fog Harbor","history":[],"playerAction":"look"}'
```

With `./server.sh` running, open http://127.0.0.1:8080/, import
`harbor.md`, open **Fog Harbor**, and start a session. Send a short action,
reload the page, reopen the scenario, resume the saved session, then send a
second short action without changing the scenario.
Scenarios and sessions are stored in this browser's IndexedDB, so they persist
across a reload of the same browser profile; the server has no session storage.

For a request-level check, use the browser's Network panel: both `POST
/api/turn` requests contain the same `scenarioMarkdown`; the second request's
`history` contains the first completed turn. The server constructs the prompt
from its instructions, scenario, history and the new player action. A location
catalog, when present, is included in the scenario context.

### Cache metrics

The UI displays optional duration, prompt-token, and cache-token metrics. On
llama.cpp versions that report them, `timings.cache_n` is the reused prompt-token
count and `timings.prompt_n` is the newly evaluated prompt-token count. The
top-level `tokens_cached` and `tokens_evaluated` fields may instead describe
the final cache occupancy and total prompt length; their meaning is version
dependent. If the timing counts are unavailable, cache reuse is unknown. Do not
infer a cache hit from timing or from a second turn alone. See
[`docs/llama-cpp-prefill-verification.md`](llama-cpp-prefill-verification.md)
for the privacy-safe live verification checklist. That checklist uses a
disposable frontmatter template and covers the first turn (no fragment), a
later hidden fragment, reload/resume, and a following cache-reusing turn. Its
evidence record contains only request IDs, the retained slot identity, and
the reported reuse/evaluation counts.
