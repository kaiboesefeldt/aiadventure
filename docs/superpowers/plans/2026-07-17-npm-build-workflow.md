# npm Build Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Kotlin/JS's shared Yarn installer with npm and document a reproducible, serialized Gradle workflow.

**Architecture:** Gradle selects npm through one repository property. The Gradle wrappers export a checkout-local `npm_config_cache`, because Kotlin/JS starts npm in generated package directories that cannot see a root `.npmrc`. `AGENTS.md` makes the single-installer rule explicit for every future agent. Generated npm lockfiles remain build output and are not committed.

**Tech Stack:** Gradle 9.5.1, Kotlin 2.3.20, Kotlin/JS, npm.

## Global Constraints

- `./gradlew check` remains the full verification command.
- Do not commit `node_modules`, Gradle caches, or build output.
- Gradle runs that can install JS dependencies are serialized per checkout.
- Do not use skipped dependency-install tasks as a permanent test workaround.

---

### Task 1: Select npm and establish the lock state

**Files:**

- Modify: `gradle.properties`
- Modify: `gradlew`
- Modify: `gradlew.bat`
- Modify: `.gitignore`
- Modify: `AGENTS.md`

**Interfaces:**

- Consumes: Kotlin Gradle property `kotlin.js.yarn=false`.
- Produces: a normal `./gradlew kotlinNpmInstall` that uses npm with a local cache.

- [ ] **Step 1: Capture the failing Yarn behavior**

  Run one serialized dependency-install attempt and record that its output
  identifies Yarn or waits for the Yarn mutex:

  ```bash
  ./gradlew kotlinNpmInstall --console=plain
  ```

- [ ] **Step 2: Set the npm package-manager property**

  Add this exact line to `gradle.properties`:

  ```properties
  kotlin.js.yarn=false
  ```

- [ ] **Step 3: Set the wrapper-owned npm cache and install**

Export `npm_config_cache` from both Gradle wrappers after calculating
`APP_HOME`, pointing to the ignored `.npm-cache` directory. Then run:

  Run:

  ```bash
  ./gradlew kotlinNpmInstall --console=plain
  git status --short
  ```

  Confirm the command no longer invokes or waits for Yarn or writes to the
  user-wide npm cache. Keep generated package directories and lockfiles ignored.

- [ ] **Step 4: Document the persistent workflow**

  In `AGENTS.md`, add a short build-hygiene rule: run Gradle/Kotlin-JS setup
  commands sequentially per checkout; after interruption, check for a child
  installer before retrying; use `./gradlew check` without excluding install
  tasks for final verification.

- [ ] **Step 5: Verify the whole build**

  Run:

  ```bash
  ./gradlew check --console=plain
  git diff --check
  ```

  Expected: both exit 0 and browser tests execute without Yarn mutex waiting.

- [ ] **Step 6: Commit configuration, lock state, documentation, and plan**

  ```bash
  git add gradle.properties gradlew gradlew.bat .gitignore AGENTS.md docs/superpowers/plans/2026-07-17-npm-build-workflow.md
  git commit -m "build: use npm for Kotlin JS dependencies"
  ```
