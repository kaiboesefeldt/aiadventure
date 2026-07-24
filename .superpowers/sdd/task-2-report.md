# Task 2: Lifecycle-safe launcher report

## RED

Command:

```bash
bash scripts/test-server-launcher.sh
```

Output:

```text
bash: server.sh: No such file or directory
```

The acceptance script failed before implementation because the required launcher
did not exist.

## Implementation

- Added executable `server.sh`.
- It loads `.env`, validates the four required configuration values, and rejects
  invalid model paths, thread/port values, ports above 65535, and unsupported
  log levels.
- It starts `llama-server` with the required arguments, starts Gradle with the
  required server arguments, and records both child PIDs.
- Its idempotent `EXIT` cleanup terminates only live children, waits for each,
  and removes the explicit launcher-created slot directory. `INT` and `TERM`
  preserve conventional 130 and 143 exit statuses.
- Updated the acceptance script to create a readable regular model fixture and
  derive the generated `--slot-save-path` from the recorded fake llama-server
  arguments before asserting cleanup.

## GREEN

Commands:

```bash
chmod +x server.sh scripts/test-server-launcher.sh
git diff --summary
bash scripts/test-server-launcher.sh
```

Output: no output; exit status 0.

Final verification:

```bash
git diff --check
bash -n server.sh scripts/test-server-launcher.sh
bash scripts/test-server-launcher.sh
```

Output: no output; exit status 0.

The acceptance test confirms the exact llama.cpp argument order and values,
signal-driven launcher termination, fake llama-server termination, and removal
of the dynamically created slot directory.

## Changed files

- `server.sh`
- `scripts/test-server-launcher.sh`
- `.superpowers/sdd/task-2-report.md`

## Self-review

- Configuration loading matches the specified root-directory and `.env` flow.
- Validation accepts log levels case-insensitively and enforces positive numeric
  threads/ports plus the 65535 upper port bound.
- PID and slot variables are initialized before traps.
- Cleanup is idempotent, avoids signalling unrelated/non-live PIDs, preserves
  the original exit status, waits for both known children, and removes only the
  known slot directory.
- `server.sh` and the acceptance script both have mode `755`.

## Concerns

None. The focused launcher acceptance test is the requested verification;
`./gradlew check` was not run because this task changes shell launcher/test
files only and the acceptance harness does not need a real Gradle server run.
