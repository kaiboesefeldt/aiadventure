# Context Compression Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement and review these tasks.

**Goal:** Automatically keep long adventures within the Primary model context using persistent checkpoints.

**Architecture:** Browser-owned raw transcript plus active/pending checkpoint artifacts. Server selects deterministic prompt epochs and runs token-budgeted recap maintenance on the existing coordinator slot.

**Tech Stack:** Kotlin JVM/JS, kotlinx.serialization/coroutines, Ktor, native llama.cpp HTTP API.

## Global Constraints

- Follow AGENTS.md and docs/superpowers/specs/2026-09-22-context-compression-design.md.
- Preserve original transcript, stateless server restart behavior, deterministic prefix and retained-slot cache contracts.
- Use the exact wire DTO names/types from the design; keep defaults backward compatible.
- Treat recap content as untrusted; never mutate authoritative world state from a recap.
- Serialize Gradle execution in this checkout. No runtime processes left at handoff.

### Task 1: Backend epochs and maintenance

Own `server/src` only. Add failing tests for validated recap projection and limits before production changes. Implement serializable artifacts, common effective-history construction in PromptFactory, and coverage/fingerprint validation. Add actual templated token counting and retained-slot context capacity lookup in LlamaCppGateway; bound native completion output. Integrate planned maintenance and activation in PrefillCoordinator and Application, returning artifacts through StreamDone. Preserve raw requests, runtime fragments and world state through every conversion. Replace the 200-entry ceiling with a bounded limit suitable for long games. Add explicit context-limit errors, safe maintenance-failure recovery and numerical diagnostics.

Tests must cover source mismatch, pending activation after three turns or earlier pressure, repeated epochs, static scenario/action overflow, empty and oversized recap, maintenance scheduling/cancellation, unchanged cache contracts and compressed warmup/prefill/turn slot reuse. Run `./gradlew :server:test` after coordinating the shared Gradle lock. Report exact red/green results and touched interfaces.

### Task 2: Browser artifact roundtrip

Own `web/src` only. First add tests exercising incoming done-event artifacts, legacy save defaults, durable save/reload, all three outgoing request mappings, unchanged full raw history, and failure preservation. Implement the design DTOs in model/Models.kt, add the default field to GameSession and request/completion DTOs, propagate in request mappers and StreamDone parsing, and save atomically with completed turns in TurnSubmission. No new hidden transcript messages or markdown rendering of recaps. Run `./gradlew :web:jsTest` after coordinating Gradle access.

### Task 3: Integration and review

Review backend and browser wire agreement, long-session API acceptance, maintenance races and exact cache-prefix tests. Run `./gradlew check` without exclusions. Have an independent subagent review the full diff, resolve important findings, re-run relevant tests, update roadmap/README to implemented behavior, and commit all implementation and relevant design/plan files together. Leave the feature branch/worktree available for user review; do not merge without user request.
