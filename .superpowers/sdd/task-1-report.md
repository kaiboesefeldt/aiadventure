# Task 1 implementer report

## Status

Implemented the initial `world-state` frontmatter parser and committed it as requested.

## Files changed

- `web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt`
  - `ParsedScenario` now exposes `initialWorldState`.
  - Frontmatter is decoded once, then used for runtime fragments and initial state.
  - Added strict validation for `world-state`, its entity mappings, and fact values.
- `web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt`
  - Added populated/default state and malformed-state coverage.
- `web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioImportTest.kt`
  - Added import rejection coverage for malformed world state.

## TDD evidence

RED:

```text
./gradlew :web:compileTestKotlinJs
FAILED: ScenarioFrontmatterTest.kt unresolved reference initialWorldState (three assertions)
```

GREEN:

```text
./gradlew :web:compileTestKotlinJs
BUILD SUCCESSFUL

./gradlew :web:browserTest
BUILD SUCCESSFUL
```

The project exposes browser tests as `:web:browserTest`; `:web:jsTest` does not exist and `:web:test` does not support `--tests`, so the focused web test task was used.

## Final verification

```text
./gradlew :server:test && ./gradlew check
BUILD SUCCESSFUL
```

An earlier `./gradlew check` saw a transient missing Gradle binary test-result file while a prior test invocation was settling. No source test failed; rerunning the server test first and then the full gate completed successfully.

## Self-review

- Confirmed no-frontmatter and omitted-state paths produce `WorldState()` with revision `0`.
- Confirmed `world-state` allows only `entities` and `facts`; entity mappings require exactly non-blank `kind`, `name`, and `summary`; facts require non-blank string keys and string values (including blank values).
- Confirmed root frontmatter remains non-strict and runtime-fragment behavior remains unchanged.
- Ran `git diff --check`; no whitespace errors.

## Concerns

None for the requested scope. Existing Gradle/Kotlin-JS deprecation and webpack bundle-size warnings remain unrelated to this change.

## Review fix: preserve source key typing

Added regression coverage that an unquoted numeric entity ID and fact key are rejected with `IllegalArgumentException("Invalid scenario frontmatter.")`.

TDD evidence:

```text
RED: ./gradlew :web:browserTest
61 tests completed, 2 failed
ScenarioFrontmatterTest.rejectsNumericEntityId FAILED
ScenarioFrontmatterTest.rejectsNumericFactKey FAILED
```

The root cause was js-yaml's object mapper: it converts a typed mapping key with `String(keyNode)` before `Object.entries` exposes it. The parser now checks each block mapping key's source YAML scalar before that coercion and rejects non-string scalars.

```text
GREEN: ./gradlew :web:browserTest
BUILD SUCCESSFUL in 6s
```

## Re-review fix: block scalar source-key detection

The previous source-key check treated every raw line containing `:` as a block
mapping key. Consequently, a literal entity summary such as `summary: |` with
the content `2026: arrival` was read as the numeric key `2026` and rejected.

Added regression coverage for that valid literal summary and for quoted numeric
entity/fact keys, while retaining the existing rejection tests for unquoted
numeric entity IDs and fact keys.

TDD evidence:

```text
RED: ./gradlew :web:browserTest
62 tests completed, 1 failed
ScenarioFrontmatterTest.parsesBlockScalarEntitySummaryContainingColon FAILED
IllegalArgumentException: Invalid scenario frontmatter.
```

The minimal fix scans only actual block-mapping entry lines: it respects quoted
keys, recognizes literal/folded scalar headers, and ignores subsequent lines
that remain more deeply indented than the scalar header. Source YAML scalars
for actual keys are still loaded and required to be strings, so unquoted
numeric keys remain invalid while quoted numeric keys remain valid.

```text
GREEN: ./gradlew :web:browserTest
BUILD SUCCESSFUL in 6s
ScenarioFrontmatterTest: 17 tests, 0 failures
ScenarioImportTest: 8 tests, 0 failures

FINAL: ./gradlew check --console=plain
BUILD SUCCESSFUL in 9s
27 actionable tasks: 1 executed, 26 up-to-date
```

`git diff --check` also completed without whitespace errors. The existing
Kotlin/JS deprecation and webpack bundle-size warnings remain unrelated.

## Re-review fix: flow mappings and scoped source keys

Added regressions for unquoted numeric keys in flow-style `entities` and
`facts` mappings, and for an ignored numeric root-frontmatter key. The source
key scan now follows only the `world-state.entities` and `world-state.facts`
paths, including their flow mappings; it no longer makes root frontmatter
strict.

TDD evidence:

```text
RED: ./gradlew :web:browserTest --tests 'de.aidadventure.web.persistence.ScenarioFrontmatterTest' --tests 'de.aidadventure.web.persistence.ScenarioImportTest'
28 tests completed, 3 failed
ScenarioFrontmatterTest.rejectsNumericEntityIdInFlowMapping FAILED
ScenarioFrontmatterTest.rejectsNumericFactKeyInFlowMapping FAILED
ScenarioFrontmatterTest.ignoresNumericRootFrontmatterKey FAILED

GREEN: ./gradlew :web:browserTest --tests 'de.aidadventure.web.persistence.ScenarioFrontmatterTest' --tests 'de.aidadventure.web.persistence.ScenarioImportTest'
BUILD SUCCESSFUL in 20s

FINAL: ./gradlew check --console=plain --quiet
Exit code 0
```

## Re-review fix: aliased world-state source mapping

An alias such as `world-state: *state` can point at a non-strict root mapping.
The scoped raw-key scan then does not observe unquoted numeric `entities` or
`facts` keys, while `Object.entries` exposes their coerced string names after
js-yaml resolves the alias.

Added a regression for an anchored root `state` mapping with numeric entity and
fact keys aliased through `world-state`, plus a direct valid flow-mapping test.
Aliases are rejected only where they replace the source map that must be
raw-key-validated: `world-state`, or its `entities`/`facts` map values. Direct
block and flow maps, quoted numeric strings, non-strict root keys, and runtime
fragments remain unchanged.

TDD evidence:

```text
RED: ./gradlew :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest
21 tests completed, 1 failed
ScenarioFrontmatterTest.rejectsWorldStateAliasWithNumericSourceKeys FAILED

GREEN: ./gradlew :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest
BUILD SUCCESSFUL in 9s

FOLLOW-UP: ./gradlew :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest
BUILD SUCCESSFUL in 8s

FINAL: ./gradlew check --console=plain
BUILD SUCCESSFUL
```

## Final strict-source validation fix: anchors, tags, merges, and aliases

Added four regressions for source syntax that can hide mapping structure from
the strict `world-state` validator:

- an anchored flow `entities` mapping with an unquoted numeric entity ID;
- a tagged flow `entities` mapping with an unquoted numeric entity ID;
- a YAML merge that injects an unquoted numeric entity ID;
- an aliased entity mapping.

RED:

```text
./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --console=plain
26 tests completed, 4 failed
ScenarioFrontmatterTest.rejectsAnchoredFlowEntitiesMapping FAILED
ScenarioFrontmatterTest.rejectsTaggedFlowEntitiesMapping FAILED
ScenarioFrontmatterTest.rejectsMergedEntityMapping FAILED
ScenarioFrontmatterTest.rejectsAliasedEntityMapping FAILED
```

The parser now rejects YAML anchors, tags, aliases, and merge keys when they
occur in the source of strict `world-state`, `entities`, `facts`, or entity
mappings. The detection is scoped to YAML node syntax, so quoted scalar text,
normal direct block/flow mappings, and non-strict root frontmatter stay valid.

GREEN and final verification:

```text
./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --console=plain
BUILD SUCCESSFUL

./gradlew --no-daemon :web:browserTest --console=plain
BUILD SUCCESSFUL

./gradlew --no-daemon :server:test --console=plain --quiet
BUILD SUCCESSFUL

./gradlew --no-daemon check --console=plain
BUILD SUCCESSFUL
27 actionable tasks: 1 executed, 26 up-to-date
```

The first complete check after browser verification encountered a missing
temporary server test-result file from an interrupted run. No source test
failed; the focused server test regenerated its results, and the immediately
following full gate completed successfully.

## Final review fix: standalone strict-node continuations

The raw YAML scanner previously skipped every non-mapping line before checking
the current strict-node path. Therefore standalone aliases and node properties
could continue a strict mapping without being audited, for example
`world-state.entities: *defaults` written on its own indented line.

Added regressions for standalone continuations inside each strict mapping:

- an anchor inside `world-state`;
- an alias inside `entities`;
- a tag inside `facts`;
- an anchor inside an entity mapping.

RED:

```text
./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --console=plain
30 tests completed, 4 failed
ScenarioFrontmatterTest.rejectsStandaloneAnchorContinuationInsideWorldState FAILED
ScenarioFrontmatterTest.rejectsStandaloneAliasContinuationInsideEntities FAILED
ScenarioFrontmatterTest.rejectsStandaloneTagContinuationInsideFacts FAILED
ScenarioFrontmatterTest.rejectsStandaloneAnchorContinuationInsideEntityMapping FAILED
```

The scanner now checks a standalone `&`, `!`, or `*` YAML node line against
the already-open strict path before ignoring non-mapping source lines. The
check is scoped to `world-state`, `entities`, `facts`, and entity mappings, so
root frontmatter remains non-strict and direct valid YAML remains supported.

GREEN and final verification:

```text
./gradlew --no-daemon :web:browserTest --tests de.aidadventure.web.persistence.ScenarioFrontmatterTest --console=plain
BUILD SUCCESSFUL in 12s

./gradlew --no-daemon :web:browserTest --console=plain
BUILD SUCCESSFUL in 11s

./gradlew --no-daemon check --console=plain --quiet
Exit code 0
```
