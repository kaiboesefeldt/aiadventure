# Running AI Adventure

## Prerequisites

- JDK 21 or newer, Bash, and a supported desktop browser
- A local `llama-server` from [llama.cpp](https://github.com/ggml-org/llama.cpp)
- A compatible GGUF model and enough memory to run it; requirements depend on the model

The exact llama.cpp version, model and hardware combination has not yet been
documented as a reproducible reference setup. The required server endpoints are
listed under [Long adventures](#long-adventures).

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

### Debug panel

The session's debug panel keeps a separate, initially expanded card for each
emitted text, flag assignment (including unchanged values), and detected signal.
Cards show the source and link back to the associated turn. Click a title to
collapse it; use the information icon for technical details. Diagnostics stay in
this browser with the session, including results that finish in the background.
“Clear debug log” removes the session's diagnostics.

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
