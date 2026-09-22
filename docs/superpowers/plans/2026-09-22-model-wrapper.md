# Model Wrapper Implementation Plan

> Execute inline using the executing-plans skill; the user approved implementation.

**Goal:** Allow either local model to start through a configured shell wrapper.

**Architecture:** Extend validation and command assembly in `server.sh`, retaining
the existing process groups and temporary primary slot directory. Exercise the
launcher in temporary checkouts with fake model and Gradle processes.

**Tech Stack:** Bash, Python standard-library unittest, Gradle/Kotlin.

## Constraints

- Script variables override model path and threads independently for each role.
- Forward `llama-server` first, then application-required arguments.
- Preserve prompt/cache behavior and terminate all owned process groups.
- Do not change the user's `.env` or wrapper scripts.

## Task 1: Wrapper launch and regression coverage

- [x] Replace the existing launcher test's shared `.env` mutation and argument-file
  race with isolated fixtures in `scripts/test_server_launcher.py`; retain
  `scripts/test-server-launcher.sh` as the entry point.
- [x] Run `bash scripts/test-server-launcher.sh`; confirm wrapper tests fail
  because `MODEL_PATH`/`THREADS` are still required.
- [x] Add per-role validation and array-based launch commands in `server.sh`.
  Keep process group IDs until cleanup, including when a wrapper exits first.
- [x] Run the launcher tests to verify legacy mode, both wrappers, mixed modes,
  paths with spaces, precedence, invalid scripts/ports, shutdown and early exit.
- [x] Document optional script variables and the wrapper calling convention in
  `.env.example` and `README.md`.
- [x] Run `./gradlew check`, `git diff --check`, inspect the final diff and verify
  no test runtime processes remain.

## Verification outcome

- All 12 isolated launcher tests passed, including relative paths and cleanup.
- The actual model wrapper forwarded the required options to a capture executable;
  no real model was loaded.
- Bash syntax checks and `git diff --check` passed; independent review had no findings.
- `./gradlew check` and `./gradlew check --continue` both reported the same existing
  failure: `WorldStateResolverTest` expects a different narrative-focus prompt.
  Its test and implementation files are identical to HEAD. Of 243 server tests,
  242 passed; the web check passed. No unrelated prompt/test changes were made.
