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

for variable_name in PRIMARY_MODEL_PATH PRIMARY_THREADS PRIMARY_PORT LOG_LEVEL; do
    if [[ -z "${!variable_name:-}" ]]; then
        echo "Missing required configuration: $variable_name" >&2
        exit 1
    fi
done

if [[ ! -f "$PRIMARY_MODEL_PATH" || ! -r "$PRIMARY_MODEL_PATH" ]]; then
    echo "PRIMARY_MODEL_PATH must be a readable regular file." >&2
    exit 1
fi

if [[ ! "$PRIMARY_THREADS" =~ ^[1-9][0-9]*$ ]]; then
    echo "PRIMARY_THREADS must be a positive integer." >&2
    exit 1
fi

if [[ ! "$PRIMARY_PORT" =~ ^[1-9][0-9]*$ ]] || (( ${#PRIMARY_PORT} > 5 )) || (( PRIMARY_PORT > 65535 )); then
    echo "PRIMARY_PORT must be an integer between 1 and 65535." >&2
    exit 1
fi

case "${LOG_LEVEL^^}" in
    TRACE|DEBUG|INFO|WARN|ERROR) ;;
    *)
        echo "LOG_LEVEL must be TRACE, DEBUG, INFO, WARN, or ERROR." >&2
        exit 1
        ;;
esac

llama_pid=""
gradle_pid=""
slot_directory=""
cleaned_up=false

cleanup() {
    local exit_status=$?

    if [[ "$cleaned_up" == true ]]; then
        return "$exit_status"
    fi
    cleaned_up=true

    for pid in "$llama_pid" "$gradle_pid"; do
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill -TERM "$pid" 2>/dev/null || true
        fi
    done

    for pid in "$llama_pid" "$gradle_pid"; do
        if [[ -n "$pid" ]]; then
            wait "$pid" 2>/dev/null || true
        fi
    done

    if [[ -n "$slot_directory" ]]; then
        rm -rf -- "$slot_directory"
    fi

    return "$exit_status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

slot_directory="$(mktemp -d "${TMPDIR:-/tmp}/ai-adventure-primary-slots.XXXXXX")"

llama-server -t "$PRIMARY_THREADS" -rea off --port "$PRIMARY_PORT" \
    --slot-save-path "$slot_directory" --model "$PRIMARY_MODEL_PATH" &
llama_pid=$!

./gradlew :server:run --args="--llama-port=$PRIMARY_PORT --log-level=$LOG_LEVEL" &
gradle_pid=$!

while [[ -n "$llama_pid" && -n "$gradle_pid" ]]; do
    if wait -n -p completed_pid "$llama_pid" "$gradle_pid"; then
        completed_status=0
    else
        completed_status=$?
    fi

    if [[ "$completed_pid" == "$llama_pid" ]]; then
        llama_pid=""
        exit "$completed_status"
    fi

    gradle_pid=""
    exit "$completed_status"
done
