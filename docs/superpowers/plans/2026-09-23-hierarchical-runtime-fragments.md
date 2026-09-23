# Hierarchical Runtime Fragments Implementation Plan

> **For agentic workers:** Execute the parser change in the feature worktree and keep the task focused on normalizing YAML into the existing runtime-fragment model.

**Goal:** Allow nested `runtime-fragments` mappings whose key path becomes the embedding match text.

**Architecture:** Recursively walk mappings in `ScenarioFrontmatter`. String values are leaves; nested mappings are grouping nodes. Each leaf's `matchText` is all ancestor keys joined with `\n`. Preserve the existing `RuntimeFragmentDefinition` representation and flat string syntax.

**Tech Stack:** Kotlin/JS, js-yaml.

## Global Constraints

- Keep the existing flat string YAML form valid; use direct strings for all leaves.
- Keep using `RuntimeFragmentDefinition(matchText, fragment)` as the output model.
- Do not run or add tests unless the user asks for verification.

---

### Task 1: Flatten hierarchical YAML during frontmatter parsing

**Files:**
- Modify: `web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt`
- Modify: `examples/test-game.md`

**Interfaces:**
- Consumes: `runtime-fragments` YAML mapping.
- Produces: flat `List<RuntimeFragmentDefinition>` consumed by the existing embedding and resolution flow.

- [x] Walk nested maps recursively while carrying the ordered key path.
- [x] Convert string leaves into definitions, joining path keys with one newline.
- [x] Keep rejecting blank keys, blank fragment text, empty nested groups, arrays, and scalars of unsupported types.
- [x] Group the example scenario's fragments under coherent location or investigation parents without changing their leaf prose.
- [x] Inspect the changed parser and diff for compatibility; do not run tests unless the user requests verification.
