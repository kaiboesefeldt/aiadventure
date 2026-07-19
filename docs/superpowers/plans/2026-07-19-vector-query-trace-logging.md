# Vector Query TRACE Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task.

**Goal:** TRACE-log the selected runtime fragment and similarity from each successful vector query.

**Architecture:** Keep the log at `RuntimeFragmentIndexRegistry.query`, where `VectorStore.QueryResult` still provides both fields. An injectable sink allows deterministic tests; production uses SLF4J only when TRACE is enabled.

**Tech Stack:** Kotlin/JVM, SLF4J, kotlin.test.

## Global Constraints

- No change to Lucene selection, resolver output, prompt order, cache, or API.
- TRACE is lazy and escaped; no result means no record.

### Task 1: Registry TRACE result diagnostic

**Files:**

- Modify: `server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentIndexRegistry.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentIndexRegistryTest.kt`

- [ ] Add a failing test using an injected recording sink that asserts the selected fragment and its similarity are recorded once, while no hit and disabled TRACE emit nothing.
- [ ] Run `./gradlew :server:test --tests de.aidadventure.server.runtimefragments.RuntimeFragmentIndexRegistryTest` and confirm RED.
- [ ] Add the smallest internal trace-sink adapter and call it only after a top `QueryResult` exists and tracing is enabled; escape fragment text consistently with the LLM TRACE logger.
- [ ] Re-run the focused test, then `./gradlew :server:test` and `./gradlew check`.
- [ ] Commit source, test, plan, and specification with message `feat: trace runtime fragment vector results`.
