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

for variable_name in PRIMARY_PORT SECONDARY_PORT LOG_LEVEL; do
    if [[ -z "${!variable_name:-}" ]]; then
        echo "Missing required configuration: $variable_name" >&2
        exit 1
    fi
done

validate_model() {
    local role="$1"
    local script_variable="${role}_MODEL_SCRIPT"
    local model_variable="${role}_MODEL_PATH"
    local threads_variable="${role}_THREADS"
    local script="${!script_variable:-}"

    if [[ -n "$script" ]]; then
        if [[ ! -f "$script" || ! -r "$script" || ! -x "$script" ]]; then
            echo "$script_variable must be a readable executable regular file." >&2
            exit 1
        fi
    else
        local variable_name
        for variable_name in "$model_variable" "$threads_variable"; do
            if [[ -z "${!variable_name:-}" ]]; then
                echo "Missing required configuration: $variable_name" >&2
                exit 1
            fi
        done
        if [[ ! -f "${!model_variable}" || ! -r "${!model_variable}" ]]; then
            echo "$model_variable must be a readable regular file." >&2
            exit 1
        fi
        if [[ ! "${!threads_variable}" =~ ^[1-9][0-9]*$ ]]; then
            echo "$threads_variable must be a positive integer." >&2
            exit 1
        fi
    fi
}

validate_model PRIMARY
validate_model SECONDARY

if [[ ! "$PRIMARY_PORT" =~ ^[1-9][0-9]*$ ]] || (( ${#PRIMARY_PORT} > 5 )) || (( PRIMARY_PORT > 65535 )); then
    echo "PRIMARY_PORT must be an integer between 1 and 65535." >&2
    exit 1
fi
if [[ ! "$SECONDARY_PORT" =~ ^[1-9][0-9]*$ ]] || (( ${#SECONDARY_PORT} > 5 )) || (( SECONDARY_PORT > 65535 )); then echo "SECONDARY_PORT must be an integer between 1 and 65535." >&2; exit 1; fi

case "${LOG_LEVEL^^}" in
    TRACE|DEBUG|INFO|WARN|ERROR) ;;
    *)
        echo "LOG_LEVEL must be TRACE, DEBUG, INFO, WARN, or ERROR." >&2
        exit 1
        ;;
esac

llama_pid=""
secondary_llama_pid=""
gradle_pid=""
slot_directory=""
cleaned_up=false
shutdown_grace_seconds=5

process_group_alive() {
    [[ -n "$1" ]] && kill -0 -- "-$1" 2>/dev/null
}

signal_process_group() {
    local signal="$1"
    local pid="$2"

    if process_group_alive "$pid"; then
        kill "-$signal" -- "-$pid" 2>/dev/null || true
    fi
}

cleanup() {
    local exit_status=$?

    if [[ "$cleaned_up" == true ]]; then
        return "$exit_status"
    fi
    cleaned_up=true
    trap '' INT TERM

    for pid in "$llama_pid" "$secondary_llama_pid" "$gradle_pid"; do
        signal_process_group TERM "$pid"
    done

    for ((second = 0; second < shutdown_grace_seconds; second++)); do
        all_groups_stopped=true
        for pid in "$llama_pid" "$secondary_llama_pid" "$gradle_pid"; do
            if process_group_alive "$pid"; then
                all_groups_stopped=false
                break
            fi
        done
        if [[ "$all_groups_stopped" == true ]]; then
            break
        fi
        sleep 1
    done

    for pid in "$llama_pid" "$secondary_llama_pid" "$gradle_pid"; do
        signal_process_group KILL "$pid"
    done

    for pid in "$llama_pid" "$secondary_llama_pid" "$gradle_pid"; do
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

model_command() {
    local role="$1"
    shift
    local script_variable="${role}_MODEL_SCRIPT"
    local model_variable="${role}_MODEL_PATH"
    local threads_variable="${role}_THREADS"
    local script="${!script_variable:-}"

    if [[ -n "$script" ]]; then
        # Resolve relative files against the project, not the executable PATH.
        [[ "$script" == /* ]] || script="$ROOT_DIR/$script"
        command=("$script" llama-server "$@")
    else
        command=(llama-server -t "${!threads_variable}" "$@" --model "${!model_variable}")
    fi
}

model_command PRIMARY -rea off --port "$PRIMARY_PORT" --slot-save-path "$slot_directory"
setsid "${command[@]}" &
llama_pid=$!
model_command SECONDARY -rea off --port "$SECONDARY_PORT"
setsid "${command[@]}" &
secondary_llama_pid=$!

setsid ./gradlew --console=plain :server:run --args="--llama-port=$PRIMARY_PORT --secondary-llama-port=$SECONDARY_PORT --log-level=$LOG_LEVEL" &
gradle_pid=$!

# Retain every group ID: an exited wrapper can still have live children.
if wait -n "$llama_pid" "$secondary_llama_pid" "$gradle_pid"; then
    exit 0
else
    exit "$?"
fi
