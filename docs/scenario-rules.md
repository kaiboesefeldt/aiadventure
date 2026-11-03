# Scenario rules

### Signal event checks

Scenario frontmatter can declare signals independently of retrieval guidance:

```yaml
flags: {visited: false}
signals:
  enters-tower:
    event-check: The player actually entered the tower during this exchange.
    condition: {visited: false}
    triggers:
      - set-flag: {visited: true}
      - emit-text: The keeper now trusts the player; reveal the hidden stair on the next round.
```

A nonblank `event-check` describes an actual event, rather than
an intention or a relevant fragment. `condition` optionally requires declared
flag values and/or a location; all entries must match (AND). Explicit event checks use only this signal condition, regardless
of conditions on fragments referencing the signal. Checks run after the completed
exchange, using flags from the start of the turn; successful assignments apply in
declaration order without triggering further checks in that same turn.

`emit-text` accepts nonblank fixed text, and text-only signals work without a
`flags` declaration. Successful checks add their texts to the next round's hidden
runtime context, in signal and trigger declaration order, followed by any selected
runtime fragment. Blank lines separate these paragraphs in one context entry.
This entry is saved with the session and survives reload without being added twice.
Text-emitting signals are checked even when their flag assignments would not change
anything; use a condition and flag assignment when an event should fire only once.

Signals with absent, null, empty, or blank `event-check` never receive automatic model checks: signal names
and fragment paths are not fallback predicates. A selected runtime fragment fires
its referenced signals when the fragment and signal conditions match the turn-start
flags and current world location. Direct rules and location entry triggers also fire
signals without model checks. A deterministic trigger suppresses an additional event
check for that signal in the same turn. The old signal `description` field is no
longer accepted; location descriptions keep their `description` field.

### Conditional runtime guidance

Runtime-fragment conditions accept multiple flags with AND semantics. A condition
list applies every matching branch independently; an optional final `else` applies
only when none of its sibling branches match. `then` and `else` contain nonempty
mappings and can nest conditions, direct actions, and named fragments:

```yaml
flags: {visited: false, clue-known: false, hint-sent: false}
signals:
  unlock-hint:
    triggers:
      - set-flag: {hint-sent: true}
      - emit-text: Connect the clue to the keeper's hidden stair.
runtime-fragments:
  condition:
    - visited: true
      clue-known: true
      hint-sent: false
      then:
        emit-text: The player can now combine the tower and clue evidence.
        signal: unlock-hint
    - clue-known: true
      then:
        emit-text: Keep the discovered clue in the next round's context.
    - else:
        emit-text: Keep the missing clue unresolved.
  "The player examines the keeper's stair": Reveal the weathered handrail.
```

A `condition` directly under `runtime-fragments` evaluates before embedding
retrieval. Its direct `emit-text` and `signal` actions need no match text or model
check. A direct signal runs its declared actions without an event classifier, provided
its own signal condition also matches the original flags. An undescribed signal
referenced exclusively by direct rules has no independent event check.
Rules and retrieval eligibility use the original flag snapshot, so assignments
do not activate other branches or retrieval candidates in that evaluation. Both matching branches above apply when the first
branch's prerequisites hold. The first branch becomes ineligible once `hint-sent`
is true; the second repeats while `clue-known` remains true.

Named children remain candidates for semantic retrieval and relevance checks.
They inherit rule conditions; direct root text and signal actions are consumed
there and are not inherited by these children. Text and signal inheritance within
named fragment groups remains supported. Direct rule text and
selected fragments supply hidden context for following responses: direct rule
texts first, triggered signal texts next, and the selected fragment last. The
selected fragment remains effective even if its own actions change flags. Declare `flags`
or `signals` to enable metadata names, including at the runtime-fragments root;
without either declaration these keys keep their legacy meaning as fragment names.
See [the authoring guide](authoring-scenarios.md)
for nesting, validation, signal behavior, and a complete scenario.
