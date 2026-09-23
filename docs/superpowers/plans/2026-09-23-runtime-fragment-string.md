# Runtime Fragment String Shorthand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept scalar string values as shorthand for runtime-fragment definitions.

**Architecture:** Normalize each runtime-fragment YAML value in `ScenarioFrontmatter.readRuntimeFragments`. Preserve existing mapping behavior and `RuntimeFragmentDefinition` shape.

**Tech Stack:** Kotlin/JS, js-yaml, Kotlin test.

## Global Constraints

- Blank match text and fragment text remain invalid.
- Existing mapping form remains supported.
- No changes to runtime selection, server APIs, or prompt construction.

---

### Task 1: Parse string shorthand

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt`
- Test: `web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt`

**Interfaces:**
- Consumes: YAML entries under `runtime-fragments`.
- Produces: Existing `List<RuntimeFragmentDefinition>` with string shorthand normalized to `fragment`.

- [ ] Add a parser test with one scalar string and one existing mapping entry; assert both produce the expected definitions.
- [ ] Add invalid-input coverage for a blank scalar string and a non-string, non-mapping value.
- [ ] Run `./gradlew :web:jsTest` and confirm the focused parser tests pass.
- [ ] In `readRuntimeFragments`, accept `entry[1] as? String` as the fragment text; otherwise retain the current mapping lookup and blank-value validation.
- [ ] Run `./gradlew :web:jsTest` again and confirm the suite passes.
