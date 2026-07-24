# Server Launcher Shutdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `server.sh` reliably terminate all managed services after Ctrl-C, including a repeated interrupt and uncooperative child processes.

**Architecture:** `setsid` makes each child command a process-group leader, preventing terminal interrupts from reaching it directly. The launcher cleanup ignores repeated termination signals, sends `SIGTERM` to each child group, waits a bounded grace period, escalates surviving groups with `SIGKILL`, and reaps their leaders. The shell regression test supplies fake llama servers and validates normal Ctrl-C shutdown plus escalation.

**Tech Stack:** Bash, GNU/util-linux `setsid`, POSIX process signals, project shell test runner.

> **Execution note (2026-07-24):** The user explicitly requested implementation
> without a regression test. Task 1 and the test-execution steps below are
> therefore superseded; only syntax and diff validation are performed.

## Global Constraints

- Preserve the existing `.env` validation and primary llama slot-directory cleanup.
- Do not log model paths, scenario data, game history, actions, or LLM text.
- Every managed descendant must receive cleanup signals through its dedicated process group.
- Final verification is `./gradlew check` with no excluded dependency-install tasks.

---

### Task 1: Capture terminal-interrupt and escalation regressions

**Files:**
- Modify: `scripts/test-server-launcher.sh`
- Test: `scripts/test-server-launcher.sh`

**Interfaces:**
- Consumes: `server.sh` starts every managed command via `setsid` and tracks the resulting process-group leader PID.
- Produces: an executable regression test that signals the launcher's session and observes fake llama process lifecycles.

- [ ] **Step 1: Write the failing test**

Add fake-server signal logging, launch `server.sh` through `setsid`, then deliver Ctrl-C to the launcher's negative PID:

```bash
setsid ./server.sh &
launcher_pid=$!
kill -INT -- "-$launcher_pid"
wait "$launcher_pid" || true
! kill -0 "$primary_fake_llama_pid" 2>/dev/null
! kill -0 "$secondary_fake_llama_pid" 2>/dev/null
```

Run a second launch with the secondary fake server configured to ignore `SIGTERM`; assert the launcher exits within the grace-period bound and that the fake process no longer exists.

- [ ] **Step 2: Run the test to verify it fails**

Run: `scripts/test-server-launcher.sh`

Expected: FAIL because the current launcher shares the terminal process group, so the fake server observes `INT`, and it lacks bounded `SIGKILL` escalation.

- [ ] **Step 3: Retain only deterministic test fixtures**

Use separate primary and secondary PID files and preserve/restore `.env` in the test cleanup. Make the fake Gradle invocation harmless by stopping the launcher only after both fake llama processes report readiness.

- [ ] **Step 4: Run the test to verify the asserted failure is specific**

Run: `scripts/test-server-launcher.sh`

Expected: FAIL at an assertion that distinguishes direct child `INT` delivery or an un-reaped `TERM`-ignoring child, not due to environment setup.

### Task 2: Isolate and reap launcher child groups

**Files:**
- Modify: `server.sh:58-109`
- Test: `scripts/test-server-launcher.sh`

**Interfaces:**
- Consumes: launcher PID variables hold `setsid` process-group leaders.
- Produces: `cleanup()` that terminates and reaps all managed process groups within a bounded duration.

- [ ] **Step 1: Implement the minimal process-group helpers**

Add helpers that identify a live group leader and send signals to its negative PID:

```bash
process_group_alive() {
    [[ -n "$1" ]] && kill -0 "$1" 2>/dev/null
}

signal_process_group() {
    local signal="$1" pid="$2"
    process_group_alive "$pid" && kill "-$signal" -- "-$pid" 2>/dev/null || true
}
```

Start primary llama, secondary llama, and Gradle with `setsid ... &`, so each captured PID leads an isolated process group.

- [ ] **Step 2: Implement bounded cleanup**

At the start of `cleanup()`, disable `INT` and `TERM` traps. Signal all live groups with `TERM`, poll their leaders for a fixed small number of one-second intervals, signal remaining groups with `KILL`, then `wait` every non-empty tracked PID. Remove the slot directory only after reaping.

- [ ] **Step 3: Run the shell regression test to verify it passes**

Run: `scripts/test-server-launcher.sh`

Expected: exit status 0; neither fake llama PID is alive, the slot directory is gone, and the TERM-ignoring fixture was force-reaped.

- [ ] **Step 4: Run static shell validation**

Run: `bash -n server.sh && git diff --check`

Expected: exit status 0.

### Task 3: Verify the repository integration

**Files:**
- Modify: `server.sh`, `scripts/test-server-launcher.sh`
- Create: `docs/superpowers/specs/2026-07-24-server-launcher-shutdown-design.md`
- Create: `docs/superpowers/plans/2026-07-24-server-launcher-shutdown.md`

**Interfaces:**
- Consumes: passing shell regression test and valid script syntax.
- Produces: a repository-wide verified change.

- [ ] **Step 1: Run the complete project verification**

Run: `./gradlew check`

Expected: exit status 0.

- [ ] **Step 2: Confirm no agent-started service remains**

Run: `ps -eo pid=,args= | rg '([l]lama-server|[g]radle.*:server:run|de\.aidadventure\.server)' || true`

Expected: no process started by this work remains.

- [ ] **Step 3: Inspect the final change set**

Run: `git diff --check && git status --short`

Expected: no whitespace errors; only the launcher, test, design, and plan files are modified or added.
