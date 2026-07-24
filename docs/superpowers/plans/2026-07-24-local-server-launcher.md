# Local Server Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a project-root launcher that starts the configured primary llama.cpp process and application server, then stops both safely when it exits.

**Architecture:** `server.sh` owns only the two PIDs it starts and a `mktemp -d` slot directory. It loads the ignored `.env`, validates the four primary configuration values, launches `llama-server` in the background, and runs Gradle in the foreground. One idempotent cleanup function handles `INT`, `TERM`, and normal `EXIT`.

**Tech Stack:** POSIX shell with Bash process management, llama.cpp `llama-server`, Gradle.

## Global Constraints

- `.env` remains ignored and is loaded from the project root.
- The configuration keys are `PRIMARY_MODEL_PATH`, `PRIMARY_THREADS`, `PRIMARY_PORT`, and `LOG_LEVEL`.
- The llama.cpp command is exactly parameterized with `-t`, `-rea off`, `--port`, `--slot-save-path`, and `--model`.
- The application starts with `./gradlew :server:run --args="--llama-port=<PRIMARY_PORT> --log-level=<LOG_LEVEL>"`.
- Cleanup must terminate only the two children started by this script and delete only its own temporary slot directory.
- Final verification is `./gradlew check`.

---

### Task 1: Add configuration example and shell regression checks

**Files:**
- Create: `.env.example`
- Create: `scripts/test-server-launcher.sh`

**Interfaces:**
- Consumes: project-root `.env` format and executable `server.sh` to be added in Task 2.
- Produces: a safe copyable configuration template and repeatable shell-level acceptance checks.

- [ ] **Step 1: Write the failing launcher acceptance script**

Create `scripts/test-server-launcher.sh`. It creates a temporary directory containing a fake executable `llama-server` that records its arguments, creates a marker in the passed `--slot-save-path`, and waits for `TERM`. It creates a temporary `.env` in the project root with `/dev/null` as the model path, sets `PRIMARY_THREADS=4`, a free test port, and `LOG_LEVEL=INFO`; it restores any original `.env` in an `EXIT` trap. It starts `./server.sh` with the fake executable first on `PATH`, waits for the fake-server argument record, sends `TERM` to `server.sh`, and asserts:

```sh
test "$(cat "$args_file")" = "-t 4 -rea off --port $test_port --slot-save-path $slot_dir --model /dev/null"
test ! -d "$slot_dir"
! kill -0 "$fake_llama_pid" 2>/dev/null
```

The script also runs `bash -n server.sh`. Initially this must fail because `server.sh` does not exist.

- [ ] **Step 2: Run the shell acceptance check to verify it fails**

Run: `bash scripts/test-server-launcher.sh`

Expected: failure from `bash -n server.sh` because the launcher has not been created.

- [ ] **Step 3: Add the checked-in configuration template**

Create `.env.example` with exactly:

```sh
# Absolute path to the primary GGUF model.
PRIMARY_MODEL_PATH=/absolute/path/to/model.gguf
PRIMARY_THREADS=4
PRIMARY_PORT=8081
LOG_LEVEL=INFO
```

- [ ] **Step 4: Verify the template remains safely tracked**

Run: `git check-ignore -v .env .env.example`

Expected: `.env` is ignored by `.gitignore`; `.env.example` is reported as not ignored.

### Task 2: Implement lifecycle-safe launcher

**Files:**
- Create: `server.sh`
- Modify: `scripts/test-server-launcher.sh`

**Interfaces:**
- Consumes: the four exported values from `.env` and `llama-server` available on `PATH`.
- Produces: an executable `server.sh` whose child PIDs and slot directory are cleaned up on `EXIT`, `INT`, or `TERM`.

- [ ] **Step 1: Implement configuration loading and validation**

Start `server.sh` with:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
    echo "Missing .env; copy .env.example and configure it." >&2
    exit 1
fi

set -a
source .env
set +a
```

Require each variable to be non-empty, require `PRIMARY_MODEL_PATH` to be a readable regular file, require `PRIMARY_THREADS` and `PRIMARY_PORT` to match `^[1-9][0-9]*$`, require `PRIMARY_PORT` to be at most `65535`, and require `LOG_LEVEL` to be one of `TRACE`, `DEBUG`, `INFO`, `WARN`, or `ERROR` case-insensitively.

- [ ] **Step 2: Implement process start and idempotent cleanup**

Use variables initialized before traps:

```bash
llama_pid=""
gradle_pid=""
slot_directory=""
cleaned_up=false
```

Create `slot_directory="$(mktemp -d "${TMPDIR:-/tmp}/ai-adventure-primary-slots.XXXXXX")"`. Define `cleanup()` to return immediately after its first call, send `TERM` only when `kill -0 "$pid"` succeeds, wait for both PIDs without replacing the original exit status, and remove the explicit `slot_directory` with `rm -rf -- "$slot_directory"`. Register `trap cleanup EXIT` and `trap 'exit 130' INT` plus `trap 'exit 143' TERM`.

Launch and record both children:

```bash
llama-server -t "$PRIMARY_THREADS" -rea off --port "$PRIMARY_PORT" \
    --slot-save-path "$slot_directory" --model "$PRIMARY_MODEL_PATH" &
llama_pid=$!

./gradlew :server:run --args="--llama-port=$PRIMARY_PORT --log-level=$LOG_LEVEL" &
gradle_pid=$!
wait "$gradle_pid"
```

- [ ] **Step 3: Mark the launcher executable**

Run: `chmod +x server.sh scripts/test-server-launcher.sh`

Expected: both files have executable mode in `git diff --summary`.

- [ ] **Step 4: Run the acceptance script to verify it passes**

Run: `bash scripts/test-server-launcher.sh`

Expected: exit status 0; it confirms exact llama.cpp arguments, signal-driven termination, and removal of the launcher-created slot directory.

### Task 3: Document normal project startup and run full verification

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: `.env.example` and `server.sh`.
- Produces: documented one-command local startup with explicit cleanup behavior.

- [ ] **Step 1: Replace the two-command Start instructions**

Replace the separate llama.cpp and Gradle commands with:

```markdown
cp .env.example .env
# Edit .env to set PRIMARY_MODEL_PATH and, if needed, the remaining values.
./server.sh
```

Document that `server.sh` uses a unique temporary slot directory and terminates both children on Ctrl-C or `TERM`.

- [ ] **Step 2: Re-run the shell acceptance check**

Run: `bash scripts/test-server-launcher.sh`

Expected: exit status 0.

- [ ] **Step 3: Run the project gate**

Run: `./gradlew check`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Inspect and commit the intended change**

Run: `git diff --check && git status --short`

Expected: no whitespace errors; only `server.sh`, `.env.example`, `scripts/test-server-launcher.sh`, `README.md`, and this plan changed since the design commit.

```bash
git add server.sh .env.example scripts/test-server-launcher.sh README.md \
  docs/superpowers/plans/2026-07-24-local-server-launcher.md
git commit -m "feat: add local server launcher"
```
