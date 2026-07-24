#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

tmp_dir="$(mktemp -d)"
args_file="$tmp_dir/llama-server.args"
fake_llama_pid_file="$tmp_dir/llama-server.pid"
slot_dir="$tmp_dir/slots"
env_backup="$tmp_dir/original.env"
launcher_pid=""
fake_llama_pid=""
had_env=false

cleanup() {
  if [[ -n "$launcher_pid" ]] && kill -0 "$launcher_pid" 2>/dev/null; then
    kill -TERM "$launcher_pid" 2>/dev/null || true
    wait "$launcher_pid" 2>/dev/null || true
  fi
  if [[ -n "$fake_llama_pid" ]] && kill -0 "$fake_llama_pid" 2>/dev/null; then
    kill -TERM "$fake_llama_pid" 2>/dev/null || true
    wait "$fake_llama_pid" 2>/dev/null || true
  fi
  if "$had_env"; then
    mv "$env_backup" .env
  else
    rm -f .env
  fi
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

if [[ -e .env ]]; then
  cp .env "$env_backup"
  had_env=true
fi

test_port="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
cat > .env <<EOF
PRIMARY_MODEL_PATH=/dev/null
PRIMARY_THREADS=4
PRIMARY_PORT=$test_port
LOG_LEVEL=INFO
EOF

cat > "$tmp_dir/llama-server" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s' "$*" > "$FAKE_LLAMA_ARGS_FILE"
while [[ "$#" -gt 0 ]]; do
  if [[ "$1" == "--slot-save-path" ]]; then
    mkdir -p "$2"
    touch "$2/fake-slot"
    break
  fi
  shift
done
printf '%s' "$$" > "$FAKE_LLAMA_PID_FILE"
trap 'exit 0' TERM INT
while true; do
  sleep 1 &
  wait $!
done
EOF
chmod +x "$tmp_dir/llama-server"

bash -n server.sh

PATH="$tmp_dir:$PATH" \
  FAKE_LLAMA_ARGS_FILE="$args_file" \
  FAKE_LLAMA_PID_FILE="$fake_llama_pid_file" \
  ./server.sh &
launcher_pid=$!

for _ in {1..100}; do
  [[ -s "$args_file" && -s "$fake_llama_pid_file" ]] && break
  if ! kill -0 "$launcher_pid" 2>/dev/null; then
    wait "$launcher_pid"
  fi
  sleep 0.1
done
[[ -s "$args_file" && -s "$fake_llama_pid_file" ]]

fake_llama_pid="$(cat "$fake_llama_pid_file")"
kill -TERM "$launcher_pid"
wait "$launcher_pid"
launcher_pid=""

test "$(cat "$args_file")" = "-t 4 -rea off --port $test_port --slot-save-path $slot_dir --model /dev/null"
test ! -d "$slot_dir"
! kill -0 "$fake_llama_pid" 2>/dev/null
