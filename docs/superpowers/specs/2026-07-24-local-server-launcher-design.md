# Local Server Launcher Design

## Goal

Provide a project-root `server.sh` that starts one configured primary
`llama-server` instance and the application server, and reliably cleans up
both processes when the script exits.

## Configuration

`server.sh` loads the ignored project-root `.env`. It requires these values:

```sh
PRIMARY_MODEL_PATH=/absolute/path/to/model.gguf
PRIMARY_THREADS=4
PRIMARY_PORT=8081
LOG_LEVEL=INFO
```

The `PRIMARY_` prefix reserves unprefixed configuration space for a later
secondary model. The repository continues to ignore `.env`; an optional
tracked `.env.example` documents the required keys without model-specific
values.

## Process Lifecycle

The script validates that `.env` exists, all required values are non-empty,
the model file is readable, and the port and thread count are valid positive
integers. It creates a unique slot directory with `mktemp -d` and starts:

```sh
llama-server -t "$PRIMARY_THREADS" -rea off --port "$PRIMARY_PORT" \
  --slot-save-path "$slot_directory" --model "$PRIMARY_MODEL_PATH"
```

The application is then started through:

```sh
./gradlew :server:run --args="--llama-port=$PRIMARY_PORT --log-level=$LOG_LEVEL"
```

The Gradle process remains in the foreground so its output and exit status are
visible to the caller.

## Shutdown and Failure Handling

The script records both child PIDs. `INT`, `TERM`, and `EXIT` invoke one
idempotent cleanup handler: it sends `TERM` to any still-running Gradle and
`llama-server` child, waits for them, and deletes only the unique slot
directory it created. Startup failures after launching `llama-server` follow
the same cleanup path. The script does not kill unrelated processes that may
use either port.

## Verification

Shell syntax validation proves the script parses. A temporary fake
`llama-server` and Gradle wrapper exercise normal termination and signal
cleanup, asserting that both child processes end and the slot directory is
removed. `./gradlew check` remains the full repository gate.
