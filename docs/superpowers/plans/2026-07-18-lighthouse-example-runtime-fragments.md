# Lighthouse Example Runtime Fragments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Add a rich, valid runtime-fragments frontmatter block to the bundled Nebelhafen example.

**Architecture:** Only examples/test-game.md changes. A 40-entry YAML mapping precedes the existing scenario unchanged. Parser/import tests assert that this concrete example yields 40 definitions and stripped prompt Markdown.

**Tech Stack:** Markdown, YAML frontmatter, Kotlin/JS parser tests.

## Global Constraints

- Add exactly 40 short, specific runtime fragments: ten each for lighthouse, signal light/smuggling, harbor master, and tavern/rumours.
- Mapping keys are natural-language situations appropriate for semantic matching.
- Values are concise game-master instructions; do not introduce unrelated setting material.
- Keep the frontmatter valid and retain the existing Markdown body unchanged.
- Verify parsed count and run the web parser/import tests.

---

### Task 1: Add and verify the Nebelhafen fragment corpus

**Files:**

- Modify: examples/test-game.md
- Modify: web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt

**Interfaces:**

- Consumes ScenarioFrontmatter.parse(markdown: String).
- Produces a parsed example with exactly 40 RuntimeFragmentDefinition values.

- [ ] **Step 1: Write the failing example-corpus test**

~~~
@Test fun bundledLighthouseExampleContainsFortyRuntimeFragments() {
    val markdown = loadResource("examples/test-game.md")
    val parsed = ScenarioFrontmatter.parse(markdown)
    assertEquals(40, parsed.runtimeFragments.size)
    assertFalse(parsed.promptMarkdown.startsWith("---"))
}
~~~

- [ ] **Step 2: Run the focused test to verify failure**

Run: ./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --console=plain

Expected: FAIL because the bundled example has no frontmatter definitions.

- [ ] **Step 3: Add the corpus**

Prepend a YAML document with 40 runtime-fragments entries. Use ten keys each for lighthouse/environment, signal-light/smuggling, harbor-master motives, and tavern rumours. Each key describes a plausible visible situation; each fragment is one short concrete narrative instruction. Preserve the existing heading and complete body exactly after the closing delimiter.

- [ ] **Step 4: Run focused and full web verification**

Run: ./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --tests de.aidadventure.web.persistence.ScenarioImportTest --console=plain

Expected: PASS; parser returns 40 entries and body begins with the unchanged lighthouse heading.

Run: ./gradlew --no-daemon :web:compileTestKotlinJs :web:browserTest :web:test --console=plain

Expected: PASS.

- [ ] **Step 5: Commit**

~~~
git add examples/test-game.md web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt
git commit -m "docs: enrich lighthouse example fragments"
~~~

## Plan self-review

- Spec coverage: the single task adds the requested 40 balanced fragments and parser-level count/body proof.
- Placeholder scan: exact files, test, commands, expected results, and content constraints are present.
- Type consistency: ScenarioFrontmatter and RuntimeFragmentDefinition already exist; no new runtime API is introduced.

