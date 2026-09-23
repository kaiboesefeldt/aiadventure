# Primary World-State Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the Secondary-LLM process and run validated World-State resolution through the Primary session pipeline on the retained cache slot.

**Architecture:** Extend the Primary gateway with a structured World-State completion operation. The session coordinator serializes that operation after a completed turn and before canonical warmup, retaining the same session lease and slot. The HTTP handler consumes the accepted state and emits `done` only after warmup has been queued/completed.

**Tech Stack:** Kotlin/JVM, Ktor, kotlinx.serialization, llama.cpp `/completion`, Gradle Kotlin DSL, shell launcher tests.

## Global Constraints

- Preserve the deterministic Primary prompt prefix: instruction, scenario, history, world state, new action.
- Preserve same-session cache verification through `tokens_cached` and `tokens_evaluated`.
- Treat all model output as untrusted text and retain the prior state on transport or validation failure.
- Keep operational logs request-ID-only outside local TRACE diagnostics.
- Final verification is `./gradlew check` without excluding dependency-install tasks.

### Task 1: Define the coordinator World-State operation

**Files:**
- Modify: `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt`

- [ ] Add a failing test proving that World-State analysis and canonical warmup use the retained turn slot in order.
- [ ] Add a failing test proving a prefill submitted while analysis is running is queued and starts after warmup.
- [ ] Add the coordinator operation and gateway seam needed by the tests.
- [ ] Run the focused coordinator tests and confirm they pass.

### Task 2: Move structured World-State completion onto the Primary gateway

**Files:**
- Modify: `server/src/test/kotlin/de/aidadventure/server/llamacpp/LlamaCppGatewayTest.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/worldstate/WorldStateResolverTest.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt`

- [ ] Add a failing gateway test asserting the World-State `/completion` request uses the supplied Primary slot, `cache_prompt=true`, structured schema, and bounded prediction.
- [ ] Move prompt construction, structured completion, parsing, and validation behind the Primary gateway while preserving the existing state semantics.
- [ ] Keep invalid or unavailable completions as a no-op state update.
- [ ] Run the focused gateway and resolver tests.

### Task 3: Integrate the operation into the HTTP turn lifecycle

**Files:**
- Modify: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`

- [ ] Add a failing integration test proving `input_ready` precedes World-State work, while `done.worldState` contains the accepted result produced before warmup.
- [ ] Replace the direct Secondary resolver call with the coordinator operation on the Primary pipeline.
- [ ] Preserve request-ID-only failure logging and the existing post-turn fragment behavior.
- [ ] Run the focused application tests.

### Task 4: Remove Secondary configuration and launcher process

**Files:**
- Modify: `server.sh`
- Modify: `.env.example`
- Modify: `README.md`
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Modify: `server/src/test/kotlin/de/aidadventure/server/ServerRuntimeConfigTest.kt`
- Modify: `scripts/test_server_launcher.py`

- [ ] Add failing launcher/config tests proving Secondary variables and the second model process are no longer required.
- [ ] Remove Secondary runtime arguments, validation, process lifecycle, and documentation.
- [ ] Keep Primary wrapper and direct-GGUF startup behavior intact.
- [ ] Run launcher and runtime configuration tests.

### Task 5: Verify cache contracts and full build

**Files:**
- Modify: relevant cache-contract tests only if the new operation requires explicit assertions.

- [ ] Run focused Primary gateway, coordinator, application, and launcher tests.
- [ ] Run `git diff --check`.
- [ ] Run `./gradlew check` without excluding dependency-install tasks.
- [ ] Inspect `git diff`, `git status --short`, and the final process state before reporting completion.
