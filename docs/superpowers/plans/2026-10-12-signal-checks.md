# Sequential signal checks implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax for tracking.

**Goal:** Recognize signal events independently of retrieval, through sequential cached single-token checks after other background analysis.

**Architecture:** Add event descriptions to signal declarations and propagate a separate default-empty check list alongside existing assignments. Execute checks on the retained coordinator slot after fragment reranking, return final flags before next turn, then restore the canonical warm prompt.

**Tech Stack:** Kotlin/JVM, Kotlin/JS, Ktor SSE, llama.cpp, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-12-signal-checks-design.md`

## Global Constraints

- Exact uppercase Y/N; grammar restricted to Y/N; n_predict=1.
- Signal checks are the last semantic background operation and use accepted narrative focus.
- Conditions use turn-start flags; no same-turn condition cascades.
- Browser IndexedDB owns flags; no persistent server flags.
- Preserve deterministic prompt-prefix and one retained warm slot for new/resumed/following turns.
- Serialize Gradle; final verification is `./gradlew check` without task exclusions.
- Work in task worktree; merge main then remove task worktree and branch; preserve unrelated changes.

## Review Focus

- A next action submitted early waits for correct flags and no stale fragment conditions.
- Signal failures/cancellation release or restore slot without poisoning the following turn.
- Existing frontmatter declarations and exact SSE fixtures remain supported.
- Multiple references to one event avoid repeated equivalent LLM checks.
- Empty signal lists add no analysis calls and preserve existing background behavior.

### Task 1: Frontmatter and browser request propagation

Files: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt`, `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`, `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`, corresponding web tests, `examples/test-game.md`, scenario documentation.

Interfaces: local/serialized `SignalCheckDefinition(name: String, description: String, conditions: List<FlagCondition> = emptyList())`; `ParsedScenario.signalChecks` and request `signalChecks: List<SignalCheckDefinition> = emptyList()`. Preserve `signals` assignment maps.

- [x] Add parser and serialization regression tests, verify failing before implementation.
- [x] Implement explicit description/condition parsing, fallback for existing definitions, propagation for turn/prefill/warmup.
- [x] When input-ready has arrived, queue a following action without aborting the SSE carrying final flags; preserve interruption while primary generation is still running. Test that following requests receive final flags.
- [x] Update example descriptions and concise docs; run focused web tests after coordinating Gradle ownership.
- [x] Commit and review.

### Task 2: Server checks and lifecycle integration

Files: server API models, prompt factory, llama.cpp gateway, prefill coordinator, Application and their tests; extract a focused signal-check helper if useful.

Interfaces: server `SignalCheckDefinition` and request `signalChecks` matching Task 1 wire format. Internal signatures chosen consistently with existing gateway/coordinator patterns.

- [x] Add regression tests for independent recognition, exact single-token requests/shared prefix, ordering, flag snapshot and final completion, early following turns and errors; verify red.
- [x] Implement sequential checks on retained slot after rerank and accepted focus; remove fragment-based flag emission.
- [x] Return final flags without race; emit metrics/diagnostics, restore canonical prompt and retain cache-slot lifecycle.
- [x] Run server tests including existing cache contracts; commit and review.

### Task 3: Whole-branch verification and integration

- [x] Independently review full diff against spec including error/cancellation paths and browser persistence.
- [x] Address review findings through implementer; run full serialized `./gradlew check`.
- [x] Verify runtime process state, commit docs, merge main, remove task worktree/branch, report verification and live-measurement limits.

## Verification evidence

- Browser: `./gradlew :web:browserTest`, 133 tests, zero failures/errors. Parser and queued-action regressions failed before implementation.
- Server: `./gradlew :server:test`, 308 tests, zero failures/errors after review fixes. Existing new/resumed/following-turn prefix and retained-slot tests unchanged.
- Full `./gradlew check` succeeds without task exclusions. Existing compiler and JVM native-access warnings remain.
- Real-coordinator HTTP tests cover independent events, accepted focus, last-stage ordering, final SSE flags, overlapping admission and cancelled SSE settlement. Gateway HTTP fixtures assert cached single-token request shape and reported metrics; they are not a live model-quality or cost benchmark.
- Review fixes make gate observation/admission atomic, propagate the actual admitted context to Application, and settle coordinator-owned work before releasing a cancelled request's barrier.
- No game server started or restarted. Live recognition accuracy, latency and actual llama.cpp cache reuse remain to be measured with the local model.

Integration: merged into `main`; merged-tree `./gradlew check` succeeded in 1m2s (308 server tests, 133 browser tests, zero failures/errors). Task branch/worktree removed. All task build/test commands completed; interrupted test executor termination was verified from Gradle daemon logs before retry. No game runtime started or restarted.
