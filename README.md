# AI Adventure

A local-LLM interactive adventure application using a local
[llama.cpp](https://github.com/ggml-org/llama.cpp) server. The JVM server
provides the API and serves the Kotlin/JS React client.

## Prerequisites

- Java (a JVM capable of producing Java 21 bytecode)
- A local `llama-server` from [llama.cpp](https://github.com/ggml-org/llama.cpp)

## Start

Create a local configuration, set `PRIMARY_MODEL_PATH` and, if necessary, the
remaining values, then start the local process:

```bash
cp .env.example .env
# Edit .env to set PRIMARY_MODEL_PATH and, if needed, the remaining values.
./server.sh
```

`server.sh` starts one local llama.cpp server. It owns the cache slots and also
performs the structured World-State update after each visible turn. On `Ctrl-C`
or `TERM`, it terminates the llama.cpp and application children.

To use an existing model wrapper script, set this in `.env`:

```dotenv
PRIMARY_MODEL_SCRIPT=/opt/models/primary-model.sh
```

A nonempty `PRIMARY_MODEL_SCRIPT` overrides `PRIMARY_MODEL_PATH` and
`PRIMARY_THREADS`; those values can be omitted in wrapper mode. Empty script
values retain the GGUF configuration. Script files must be readable and
executable; relative paths are resolved from the project directory. Quote paths
containing spaces.

The launcher calls the script with `llama-server` as its first argument, then
`-rea off --port <configured port> --slot-save-path
<temporary directory>`. The wrapper supplies model and performance settings and
must forward the remaining arguments **after** its own options, for example:

```bash
#!/usr/bin/env bash
exec "$1" --model /opt/models/model.gguf -t 1 "${@:2}"
```

Keep the server in the foreground; do not detach it with `setsid` or daemonize it.
The launcher terminates wrapper children when stopped or when any component exits.

Open [http://127.0.0.1:8080/](http://127.0.0.1:8080/) in the browser.

The server listens only on `127.0.0.1:8080` by default.
Set `PORT` in `.env` to use another port (for example, `PORT=8088`), then open
that port in the browser. API requests use the same origin as the page.
Set `ADVENTURE_EXPOSE_LAN=true` only when you intentionally need the API reachable on
your LAN. Do not use that setting on an untrusted network.

### TRACE diagnostics

`LOG_LEVEL=TRACE` is an opt-in local diagnostic mode. It deliberately records
scenario, action, prompt and complete LLM-response content, including the raw
structured World-State response, so cache and model behavior can be inspected.
Do not enable it when those local logs may be exposed.

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

### Long adventures

The game automatically creates narrative recaps as the Primary model context fills.
It keeps recent turns verbatim and switches to a new prompt epoch after three further
completed turns, or earlier when space runs low. Recaps use the session's reserved
Primary slot; a maintenance turn can take longer to finish while you continue typing.
The full transcript and active/pending recaps remain in browser IndexedDB and survive
reloading a saved game. Recaps do not replace the authoritative world state.

Budgets use the actual chat template and tokenizer plus the retained slot's `n_ctx`.
The Primary server must expose `/apply-template`, `/tokenize`, `/slots` and `/completion`.
If the scenario, action and required response reserve cannot fit safely, the game
reports a context-limit error. An already oversized old save can require a larger
llama.cpp context; the application does not silently discard its history.

The early compaction threshold is configurable in `.env` with
`CONTEXT_COMPACTION_THRESHOLD`, as a percentage of the active slot's `n_ctx`.
It accepts integer values from `1` to `99` and defaults to `65`. The slot's actual
`n_ctx` remains the hard limit.

The browser still sends the full archive so the stateless server can verify checkpoint
coverage. Request safety limits are 100,000 history entries and 20 million history
characters, independently of the much smaller effective model prompt.

## Test-Spiel laden

Der deutsche [Guide zum Erstellen eines Spiels](docs/Spiel-Erstellen.md)
beschreibt alle unterstützten Szenariofelder und Varianten, mit vollständiger
Vorlage, Locations, Flags, Signalen und Runtime-Fragmenten.

Importiere ein Beispiel in der Browser-Oberfläche:

- [`examples/test-game.md`](examples/test-game.md): komplexes Leuchtturm-Spiel
  mit bedingten Runtime-Fragmenten und älteren Signalzuordnungen.
- [`examples/signal-test-game.md`](examples/signal-test-game.md): **Signal-Test:
  Der Leuchtturm von Nebelhafen**, mit drei Flags und vier unabhängigen
  Ereignisprüfungen. Die [manuelle Prüfanleitung](examples/signal-test-game-testing.md)
  beschreibt Ereignisse, Gegenchecks, Zustandswechsel und Debug-Metriken.

## Verify

```bash
./gradlew check
```

The command compiles both modules and runs their test suites.

Run `bash scripts/test-server-launcher.sh` for isolated launcher regression tests
(requires Python 3; does not start actual models or modify your `.env`).

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
`history` contains the first completed turn. The server uses the scenario
Markdown directly as the system message, followed by history and the new player
action. A location catalog, when present, is appended to the scenario text.

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

### Signal event checks

Scenario frontmatter can declare signals independently of retrieval guidance:

```yaml
flags: {visited: false}
signals:
  enters-tower:
    description: The player actually entered the tower during this exchange.
    condition: {visited: false}
    triggers:
      - set-flag: {visited: true}
      - emit-text: The keeper now trusts the player; reveal the hidden stair on the next round.
```

`description` is optional nonblank text describing an actual event, rather than
an intention or a relevant fragment. `condition` optionally requires one or more
declared flag values; all entries must match (AND). Explicit event descriptions use only this signal condition, regardless
of conditions on fragments referencing the signal. Checks run after the completed
exchange, using flags from the start of the turn; successful assignments apply in
declaration order without triggering further checks in that same turn.

`emit-text` accepts nonblank fixed text, and text-only signals work without a
`flags` declaration. Successful checks add their texts to the next round's hidden
runtime context, in signal and trigger declaration order, followed by any selected
runtime fragment. Blank lines separate these paragraphs in one context entry.
This entry is saved with the session and survives reload without being added twice.
Text-emitting signals are checked even when their flag assignments would not change
anything; use a condition and flag assignment when an event should fire only once.

Older signals without descriptions use their signal-bearing fragment paths and
inherited flag conditions as fallback event predicates, or the signal name when
no fragment references it, except for undescribed signals referenced exclusively
by direct rules (see below). Equivalent predicates are deduplicated; eligible paths
for the same signal form one event question. Existing `signal` references and
`triggers` assignment maps remain supported.

### Conditional runtime guidance

Runtime-fragment conditions accept multiple flags with AND semantics. A condition
list applies every matching branch independently; an optional final `else` applies
only when none of its sibling branches match. `then` and `else` contain nonempty
mappings and can nest conditions, direct actions, and named fragments:

```yaml
flags: {visited: false, clue-known: false, hint-sent: false}
signals:
  unlock-hint:
    triggers:
      - set-flag: {hint-sent: true}
      - emit-text: Connect the clue to the keeper's hidden stair.
runtime-fragments:
  condition:
    - visited: true
      clue-known: true
      hint-sent: false
      then:
        emit-text: The player can now combine the tower and clue evidence.
        signal: unlock-hint
    - clue-known: true
      then:
        emit-text: Keep the discovered clue in the next round's context.
    - else:
        emit-text: Keep the missing clue unresolved.
  "The player examines the keeper's stair": Reveal the weathered handrail.
```

A `condition` directly under `runtime-fragments` evaluates before embedding
retrieval. Its direct `emit-text` and `signal` actions need no match text or model
check. A direct signal runs its declared actions without an event classifier, provided
its own signal condition also matches the original flags. An undescribed signal
referenced exclusively by direct rules has no independent event check.
Rules and retrieval eligibility use the original flag snapshot, so assignments
do not activate other branches or retrieval candidates in that evaluation. Both matching branches above apply when the first
branch's prerequisites hold. The first branch becomes ineligible once `hint-sent`
is true; the second repeats while `clue-known` remains true.

Named children remain candidates for semantic retrieval and relevance checks.
They inherit rule conditions; direct root text and signal actions are consumed
there and are not inherited by these children. Text and signal inheritance within
named fragment groups remains supported. Direct rule text and
selected fragments supply hidden context for following responses: direct rule
texts first, triggered signal texts next, and the selected fragment last. The
fragment must still be eligible after flag assignments. Declare `flags`
or `signals` to enable metadata names, including at the runtime-fragments root;
without either declaration these keys keep their legacy meaning as fragment names.
See [the authoring guide](docs/Spiel-Erstellen.md#7-runtime-fragmente-situationsabhängige-hinweise)
for nesting, validation, signal behavior, and a complete scenario.
