# Server Launcher Shutdown Design

## Goal

`server.sh` must stop its primary llama.cpp server, secondary llama.cpp server,
and Gradle server reliably when interrupted from the terminal, without leaving
managed processes behind or waiting indefinitely.

## Cause

The launcher and its background children currently share the terminal process
group. A terminal Ctrl-C therefore interrupts the launcher and each child at
the same time. A second Ctrl-C can interrupt the launcher's cleanup waits.

## Design

Each managed command starts in a dedicated session/process group using
`setsid`. Ctrl-C reaches only the launcher. Its exit cleanup then signals every
managed process group with `SIGTERM`, waits for a bounded grace period, and
sends `SIGKILL` only to groups that remain alive. The cleanup trap ignores
further `SIGINT` and `SIGTERM` while it reaps children, so a repeated Ctrl-C
cannot abandon cleanup.

The tracked PID is the process-group leader created by `setsid`; all signalling
uses the negative PID to include descendants such as the Gradle JVM. Existing
slot-directory cleanup remains after the child groups have been reaped.

## Verification

The launcher script test will start it in a separate session, deliver Ctrl-C to
the launcher process group, and verify that both fake llama-server instances
exit and the primary slot directory is removed. A fake server that ignores
`SIGTERM` will prove bounded shutdown by requiring the escalation path.
