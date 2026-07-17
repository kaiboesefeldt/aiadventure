# Resume Cache Warmup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Warm a saved session's llama.cpp cache immediately when it is resumed, without adding a fake player action.

**Architecture:** A dedicated `WarmupRequest` carries the immutable session context but no action. The existing session coordinator starts it as a normal prefill operation, so typing prefills and a fast turn naturally wait for the same slot. The browser triggers this silent request once when `PlayView` mounts and uses the persisted session ID for all cache-aware requests.

**Tech Stack:** Kotlin/JVM, Ktor, Kotlin/JS React, kotlinx.coroutines, Kotlin test, Gradle.

## Global Constraints

- Keep prompt ordering deterministic: instruction, original scenario, history, new action.
- Do not insert an empty player action into a warmup prompt.
- A warmup and later turn use the same persisted session ID and llama.cpp slot.
- Browser IndexedDB owns sessions; server cache state is ephemeral and expires through the existing TTL.
- Logs contain request IDs and safe operational categories only.

---

### Task 1: Add a dedicated, action-free server warmup operation

**Files:**

- Modify: `server/src/main/kotlin/de/aidadventure/server/model/ApiModels.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/llamacpp/LlamaCppGateway.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/prefill/PrefillCoordinator.kt`
- Modify: `server/src/main/kotlin/de/aidadventure/server/Application.kt`
- Test: `server/src/test/kotlin/de/aidadventure/server/game/PromptFactoryTest.kt`
- Test: `server/src/test/kotlin/de/aidadventure/server/prefill/PrefillCoordinatorTest.kt`
- Test: `server/src/test/kotlin/de/aidadventure/server/ApplicationTest.kt`

**Interfaces:**

- Produces `@Serializable data class WarmupRequest(sessionId, modelId, llamaCppPort, scenarioMarkdown, history)`.
- Produces `PrefillCoordinator.warmup(request: WarmupRequest): PrefillDisposition` and `POST /api/warmup` returning `202 PrefillAccepted`.
- Produces `PromptFactory.buildWarmup(request): List<ChatMessage>` containing instruction, scenario, and history only.

- [x] **Step 1: Write failing server tests**

  Add a prompt-factory test asserting the warmup role sequence has no final
  synthetic `UserMessage`; add a coordinator test that begins `warmup`, starts
  a turn while it is active, completes warmup, and asserts identical slot IDs;
  add an application test that posts a valid warmup and observes the injected
  coordinator's `warmup` call.

- [x] **Step 2: Run the focused tests and verify RED**

  Run:

  ```bash
  ./gradlew :server:test --tests de.aidadventure.server.game.PromptFactoryTest --tests de.aidadventure.server.prefill.PrefillCoordinatorTest --tests de.aidadventure.server.ApplicationTest -x kotlinNpmInstall -x kotlinStoreYarnLock -x :web:browserProductionWebpack --console=plain
  ```

  Expected: compilation fails because `WarmupRequest`, `buildWarmup`, and
  `warmup` do not exist.

- [x] **Step 3: Implement the minimal warmup path**

  Add `WarmupRequest`, validate it with the existing common context checks but
  without `playerAction`, and route `POST /api/warmup` through the same
  `CoordinatorKey` and 202 response as prefill. Add `buildWarmup` to
  `PromptFactory`; make the gateway call `/apply-template` with that message
  list and call `/completion` with `n_predict = 0` and `cache_prompt = true`.
  Extend the coordinator's prefill operation so a warmup enters
  `State.Prefilling`, hands its retained lease to a waiting turn, and finishes
  in `State.Ready`.

- [x] **Step 4: Run the focused tests and verify GREEN**

  Repeat the Step 2 command. Expected: all selected tests pass.

### Task 2: Start the silent warmup when a saved session opens

**Files:**

- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt`
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/PrefillSubmissionTest.kt`
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/api/RequestBodyEncodingTest.kt`

**Interfaces:**

- Produces `AdventureApi.warmup(request: WarmupRequest): PrefillAccepted`.
- Uses `WarmupRequestMapper.map(session, scenario)` with `session.id` as the
  request's `sessionId`.

- [x] **Step 1: Write failing browser tests**

  Add an encoding test for `WarmupRequest`; add a submission test showing the
  resume warmup reaches the API once with the stored session ID and no player
  action.

- [x] **Step 2: Run the focused browser tests and verify RED**

  Run:

  ```bash
  ./gradlew :web:jsTest --tests de.aidadventure.web.PrefillSubmissionTest --tests de.aidadventure.web.api.RequestBodyEncodingTest --console=plain
  ```

  Expected: compilation fails because the web warmup API and mapper do not
  exist.

- [x] **Step 3: Implement the minimal browser request**

  Add the serializable web `WarmupRequest`, encoder, mapper, and
  `AdventureApiClient.warmup` POST to `/api/warmup`. In `PlayView`, replace the
  random `liveId` with `props.session.id` and invoke `api.warmup` once from a
  mount-only `useEffect`; ignore its safe failure so typing and sending remain
  available.

- [x] **Step 4: Run the focused browser tests and verify GREEN**

  Repeat the Step 2 command. Expected: all selected browser tests pass.

### Task 3: Verify and commit

**Files:**

- Modify: `docs/superpowers/plans/2026-07-16-resume-cache-warmup.md`

- [x] **Step 1: Run server verification**

  ```bash
  ./gradlew :server:test -x kotlinNpmInstall -x kotlinStoreYarnLock -x :web:browserProductionWebpack --console=plain
  git diff --check
  ```

  Expected: both commands exit 0.

- [x] **Step 2: Run the complete project check (attempted; blocked at shared Yarn installation)**

  ```bash
  ./gradlew check --console=plain
  ```

  Expected: exit 0. If the shared Yarn installation blocks, preserve its
  process state, report the blocker, and do not treat it as a product failure.

- [x] **Step 3: Commit implementation and plan**

  ```bash
  git add server web docs/superpowers/plans/2026-07-16-resume-cache-warmup.md
  git commit -m "feat(cache): warm resumed sessions"
  ```
