# Cache Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add content-safe logs correlating llama.cpp slot lifecycle, prompt identity, and cache metrics.

**Architecture:** The gateway logs rendered-prompt fingerprints and native metrics; the coordinator logs lease transitions using opaque identity. No browser or HTTP-contract changes.

**Tech Stack:** Kotlin/JVM, SLF4J, Kotlin test, Gradle.

## Global Constraints

- Never log scenario, history, player action, LLM text, model ID, raw session ID, or raw prompt.
- Preserve deterministic prompt ordering.

---

### Task 1: Gateway diagnostics

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Test: `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt`

- [ ] Write a failing test for deterministic 24-character SHA-256 prompt fingerprints that do not contain source text.
- [ ] Run `./gradlew :server:test --tests de.aidadventure.server.llamacpp.LlamaCppGatewayTest -x kotlinNpmInstall -x kotlinStoreYarnLock` and observe the missing implementation.
- [ ] Implement the fingerprint and DEBUG events `phase=prepared|completed`, containing only slot, fingerprint, prompt character count, and native cached/evaluated token totals.
- [ ] Re-run the focused test and observe PASS.

### Task 2: Coordinator lifecycle diagnostics

**Files:**
- Modify: `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt`
- Test: `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`

- [ ] Write a failing test that captures `lease_acquired`, `turn_handoff`, and `turn_retained` for one slot.
- [ ] Run `./gradlew :server:test --tests de.aidadventure.server.prefill.PrefillCoordinatorTest -x kotlinNpmInstall -x kotlinStoreYarnLock` and observe the absent event sink.
- [ ] Add an internal diagnostic sink with a default DEBUG logger; events contain only opaque coordinator hash, transition name, and slot ID.
- [ ] Re-run the focused test and observe PASS.

### Task 3: Verify and commit

- [ ] Run `./gradlew check` and confirm exit code 0.
- [ ] Run `git diff --check` and inspect all new log formats for content-bearing interpolations.
- [ ] Commit server code and both diagnostics documents as `chore(cache): add safe cache diagnostics`.
