# Guide: Creating a Game

This guide describes the scenario format currently supported by AI Adventure.
A game consists of a Markdown file with optional YAML frontmatter. The Markdown
text describes the world, rules and game master; the frontmatter configures the
start, locations, persistent flags, event signals and situational runtime
fragments. The examples show valid forms and their limits.

## 1. Quick start

Save this file as `mein-spiel.md`, import it in the interface, open the
scenario and start a new session:

```markdown
# The Missing Light

You are the game master of a mysterious harbor adventure.
The player character is looking for the missing lighthouse keeper.
Begin at the quay on a foggy night.
Respond in English in short paragraphs. Describe concrete consequences, but
leave decisions for the player character to the human.
```

Frontmatter is optional. Without configuration there are no declared locations,
flags, signals or runtime fragments; the structured starting location is empty.
At the start of a game the application automatically generates a short
introduction in the scenario's language. The player need not enter a first
action to obtain it.

Nonempty files ending in `.md` or `.markdown` are accepted, regardless of
extension case. Scenarios and saved games are stored in the current browser
profile's IndexedDB. An identical filename and content identify the same
scenario. An edited file has a different fingerprint and is imported as a new
scenario; it does not automatically overwrite the existing game.

## 2. Structure and complete field reference

```markdown
---
initial-situation:
  world-state:
    location: Kai
  setting: You stand on the quay at night. The lighthouse is dark.
locations:
  Kai: Harbor square overlooking the lighthouse.
  Leuchtturm: Old stone tower at the end of the breakwater.
flags:
  turm-besucht: false
signals:
  turm-betreten:
    event-check: The character actually entered the lighthouse during this exchange.
    condition: {turm-besucht: false}
    triggers:
      - set-flag: {turm-besucht: true}
runtime-fragments:
  "The character examines the lighthouse from outside": >
    Describe a stuck door and a second possible entrance.
---
# The Missing Light

You are the game master. Tell a mysterious harbor adventure in English.
The keeper has disappeared; a message from her lies in the tower.
Give clues gradually and accept plausible solutions.
```

| Field | Type and meaning | Optional / default when omitted |
| --- | --- | --- |
| `initial-situation` | Mapping with `world-state` and/or `setting` | Yes; no additional initial situation |
| `initial-situation.world-state` | Mapping containing exactly `location` | Yes; falls back to top-level `world-state` |
| `initial-situation.world-state.location` | Nonempty string; structured starting location | Required if this `world-state` is specified |
| `initial-situation.setting` | Nonempty string; one-time context for the introduction | Yes |
| `world-state` | Legacy starting location form containing exactly `location` | Yes; empty starting location |
| `locations` | Mapping from canonical location names to descriptions | Yes; no location catalog |
| `flags` | Mapping from flag names to `true` or `false` | Yes; no flags |
| `signals` | Mapping or list of named signals | Yes; no event checks |
| `signals.<name>.event-check` | Event description string; `null` or empty disables the model check | Yes; no automatic check without this field |
| `signals.<name>.condition` | Nonempty mapping from declared flags to desired Boolean values and/or `location` to a location name (AND) | Yes; no additional flag restriction |
| `signals.<name>.triggers` | Nonempty list of `set-flag` and/or `emit-text` actions | Required per signal |
| Signal action `set-flag` | Nonempty mapping from declared flags to Boolean target values | Optional per signal |
| Signal action `emit-text` | Nonempty fixed text for the next turn's hidden context | Optional per signal |
| `runtime-fragments` | Mapping containing named fragments and optional direct rules | Yes; no additional guidance |
| Fragment field `emit-text` | Nonempty additional text inherited by descendants | Yes |
| Fragment field `signal` | Name of a declared signal, inherited by descendants | Yes |
| Fragment field `set-flag` | Nonempty mapping from declared flags to Boolean target values, inherited by named descendants | Yes |
| Fragment field `condition` | Condition mapping (flags and/or `location`, AND), or list of independent `then` branches with optional final `else` | Yes |

The last four fragment fields have a special compatibility rule: they are
metadata only when a top-level `flags` or `signals` field exists, including
directly under `runtime-fragments`. Without either field they are ordinary
fragment names. See section 7.

Frontmatter must begin at the very start of the file with a separate `---` line
and end with another separate `---` line. Leading blank lines or a missing
closing delimiter prevent recognition; the text is then treated as ordinary
Markdown. LF and CRLF line endings are supported.

The YAML block is not sent unchanged to the game master. The application
processes its fields separately. The Markdown body forms the persistent
scenario context; the location catalog is also included in the model context.
The initial setting and current world state are passed separately. Flags
control conditions and signal checks; they are not written into the game-master
prompt as a general flag list. To give a flag change a narrative effect, write
appropriate conditional fragments or an `emit-text` signal action.

## 3. The world and game rules in Markdown

You can use any Markdown sections in the body, for example:

```markdown
# The Missing Light

## Role and Narrative Style
Lead an exciting adventure in English. Answer concisely and vividly.
Do not describe the character’s decisions or thoughts without player input.

## World and Characters
Nebelhafen depends on maritime trade. The harbor master wants to avoid a scandal.
The keeper left the tower to pursue a group of smugglers.

## Secrets and Clues
A hidden message leads to the storage shed. The harbor ledger contains
a false departure time. Either clue can be discovered independently.

## Rules and Goal
Plausible actions can succeed. Dangerous attempts have consequences.
The adventure ends when the keeper is found and the light signals are explained.
```

These headings are writing conventions, not special engine fields. You can
also describe inventory, character statistics, combat rules, puzzles, time
pressure, dialogue and endings here. These become narrative rules for the
model. They currently have no dedicated structured YAML systems or guaranteed
rule-based execution. Use flags for binary progress.

## 4. Initial situation and world state

### Recommended form

```yaml
initial-situation:
  world-state:
    location: Kai
  setting: |
    You have just arrived in Nebelhafen.
    The harbor master asks you to look for the keeper.
    The breakwater and dark lighthouse lie ahead.
```

`setting` is passed once when the introduction is generated. It is not a
standing instruction to return every later scene to the start.
`world-state.location` initializes the structured current location.

Both subfields are individually optional: only `setting`, only `world-state`,
or the empty mapping `initial-situation: {}` are valid. If a subfield is
specified, its content must conform to the required form.

### Also supported: legacy starting location form

```yaml
world-state:
  location: Kai
```

This form can also be combined with `initial-situation.setting`. If both
starting location forms are specified, `initial-situation.world-state.location`
takes precedence. For new games, use one form to keep the file unambiguous.

The current structured world state contains `location` and an automatically
determined `narrativeFocus`, a short sentence about the current action. In
frontmatter only `location` may be set for the world state; `narrativeFocus`,
`entities`, `facts` and other state fields are invalid there. After an exchange
the application updates the location and narrative focus based on the events
actually described. Invalid results do not replace a previously valid location.

## 5. Locations: defining places

```yaml
locations:
  Kai: Working quay with bollards and a view of the breakwater.
  Leuchtturm: |
    Stone tower at the end of the breakwater.
    Inside, a spiral staircase and ladder lead to the lantern room.
  Hafenkneipe: "The Crooked Mast: a sailors’ tavern."
  Lagerschuppen: ""
  Steg:
```

Plain, multiline and quoted text, an empty string, and an omitted description
(`null`) are valid. The last two produce an empty description. For entry
actions, a location can instead use a mapping with `description` and
`triggers`:

```yaml
locations:
  Kai:
    description: Working quay overlooking the breakwater.
    triggers:
      - set-flag: {hafen-betreten: true}
      - emit-text: The quay bells ring in welcome.
      - signals: [trifft-hafenmeisterin]
```

`triggers` uses the same `set-flag` and `emit-text` actions as a signal. The
additional `signals` action expects a list of declared signal names. On entry,
location actions execute in order; referenced signals are triggered and run
their own actions without a model event check. This also applies to the
configured starting location. On later turns only actual location changes
trigger the actions; warmup and resume do not repeat them.

Apart from text or `null`, location values may use only this mapping; lists,
numbers and Boolean values are invalid. If specified, `triggers` must contain
at least one valid action.

The keys are canonical location names. Names must be nonempty and cannot
differ only in case. A specified catalog must contain at least one location;
`locations: {}` is invalid. If a catalog exists, a configured starting
location must exactly match a key, including case.

With a catalog, the structured current location can only be one of the declared
locations. Descriptions help distinguish presence from mentions, travel
intentions and failed entry attempts. Without a catalog, this location analysis
does not accept a new structured location; a configured starting location
remains in place. Declare all playable locations for reliable location changes.

The catalog does not define paths, adjacency or access restrictions. Describe
these in Markdown or fragments. A fragment node with the same name is not
automatically bound to a location: its name supports semantic selection, not
an exact `location` condition.

## 6. Flags and signals: persistent progress

### Flags

```yaml
flags:
  turm-besucht: false
  nachricht-gefunden: false
  hafenmeisterin-misstrauisch: true
```

Flags are named Boolean values per saved game. They may start as `true` or
`false`, be set or reset by signals, and form conditions for fragments or
event checks. Names must be nonempty; use unique, readable strings.
`flags: {}` is valid. Strings such as `"false"`, numbers, `null`, and
`yes`/`no` are not valid flag values.

A new session takes the initial values. A resumed session uses its saved
values. A game-master statement alone does not prove a flag change: the
authoritative state is the flag state returned and stored by the engine.

### Signals as a mapping

```yaml
signals:
  nachricht-entdeckt:
    event-check: >
      The character actually found or read the hidden message during this
      exchange. Searching or intending to search alone does not count.
    condition: {nachricht-gefunden: false}
    triggers:
      - set-flag:
          nachricht-gefunden: true
          hafenmeisterin-misstrauisch: true
      - set-flag:
          turm-besucht: true
```

Each signal has a nonempty `triggers` list. Supported actions are `set-flag`
and `emit-text`; each list item contains exactly one action type. A
`set-flag` action can set several already declared flags; assignments apply in
order. `false` resets a state. A flag assigned repeatedly receives the last
assigned value.

`event-check` describes an event in the most recently completed exchange.
Phrase it as a verifiable fact and distinguish intention, attempt and success.
It is not an instruction to narrate the event. The optional `condition`
restricts the check to declared flag values and/or the current location. All
entries in the same mapping must match (AND), for example
`condition: {location: Leuchtturm, nachricht-gefunden: true, turm-besucht: false}`.
The naming and catalog rules in section 7 D apply to `location`.
Without `condition` there is no such restriction.

### Signal actions with `emit-text`

```yaml
flags: {nachricht-gefunden: false}
signals:
  nachricht-entdeckt:
    event-check: The character actually found the keeper’s message.
    condition: {nachricht-gefunden: false}
    triggers:
      - set-flag: {nachricht-gefunden: true}
      - emit-text: >
          The message mentions the departure at the third bell.
          Use this clue in the next response.
```

`emit-text` adds fixed, nonempty text to the hidden runtime context for the
next turn. It is not immediately emitted as a visible answer and does not
change the completed answer. Unlike runtime fragments, these texts do not
undergo semantic fragment selection: a successful signal check emits its texts.

A signal may contain only `emit-text` actions, in which case it does not
require a `flags` declaration:

```yaml
signals:
  glocke-gehoert:
    event-check: The character heard the harbor bell during this exchange.
    triggers:
      - emit-text: Mention the upcoming departure in the next response.
```

Texts from successful signals are collected in signal and trigger order,
followed by any selected runtime fragment. Blank lines separate the texts in a
single context entry. It is saved with the game and survives reload without
being inserted twice.

Signals emitting text can fire again on later matching events even when their
flag assignments would no longer change anything. For a one-shot event, combine
a `false` condition with a `true` assignment, as in the first example.

### Signals as an ordered list

```yaml
signals:
  - turm-betreten:
      event-check: The character actually entered the tower.
      triggers:
        - set-flag: {turm-besucht: true}
  - nachricht-entdeckt:
      event-check: The character actually found the hidden message.
      triggers:
        - set-flag: {nachricht-gefunden: true}
```

Each list item contains exactly one named signal. Signal names must be nonempty
and unique. The list makes ordering explicit. For mappings with ordinary text
names, declaration order applies; avoid purely numeric signal names because
JavaScript may sort numeric object keys differently. Empty signal mappings and
lists are valid.

### Execution and conflicts

After the visible answer, world-state/focus update and fragment check, the model
checks relevant event-based signals sequentially. Direct runtime rules are
selected before embedding search. Their signal actions are then applied with
the other signal actions but do not require a model check (section 7 G). The
check requires exactly `Y` or `N`; only an exact `Y` triggers the signal. A
failed or invalid check neither sets its flags nor emits its signal texts.

Flag conditions for event-based signal checks use the flag snapshot **at the
start of the turn**. Location conditions instead use the accepted world state
after the visible answer and its world-state evaluation. Assignments from
successful signals are applied in signal order only after the checks. There is
no same-turn cascade: a signal setting `turm-besucht: true` does not thereby
activate a previously ineligible check with that condition in the same turn.

An automatic event check without `emit-text` is skipped when all effective
target flag values have already been reached. Signals with `emit-text` remain
eligible under their conditions even without a flag change. For example, if
`alarm` begins as `false`, a signal that would only set `alarm: false` is
not checked, even if another signal could set `alarm: true` this turn. If two
conflicting signals are both eligible and succeed, the later assignment wins.
Avoid such conflicts in game design.

### Signals without `event-check`

Without `event-check`, no automatic model event check occurs. `null`, empty
text and whitespace also disable it. Neither the signal name nor fragment path
acts as a fallback. The signal can still be triggered by `signal: <name>` in
a selected runtime fragment, a matching direct rule or a location trigger.
The applicable fragment and signal conditions must be satisfied.

With `event-check`, the event can also be detected independently of fragment
selection. This model check uses the signal condition; conditions of referencing
fragments constrain only their own selection and triggering. Signals already
triggered deterministically are not checked again by the model in the same
evaluation. A signal does not require a runtime fragment. The former signal
field `description` is not supported as an alias; use `event-check` for event
checks. Location descriptions are still called `description`.

## 7. Runtime fragments: situational guidance

Runtime fragments provide additional guidance to the game master, hidden from
the normal transcript. Named keys describe the situation; their text values
describe how the game master should respond. Direct rules can also emit text
and trigger signals based on flags and/or the current location (section G).

After an exchange, direct rules are evaluated first. The application then
semantically searches named fragments eligible under their conditions for a
candidate, using the current narrative focus or latest visible exchange and
current location. A further model check can reject the candidate. At most one
fragment is selected per evaluation. Selected guidance enters the context for
subsequent answers; it does not retroactively alter the visible answer.
Selection precedes the final signal checks and does not yet take their new
flag values into account. Named fragments are therefore not synchronous
immediate triggers.

### A. Direct text leaves

```yaml
runtime-fragments:
  "The character examines the tower door": >
    Describe rust on the lock and fresh scratches on the doorframe.
  "The character questions the harbor master": |
    She evades questions about the last departure.
    Give a verifiable contradiction, but do not reveal the secret yet.
```

Plain, quoted and multiline nonempty strings are valid. YAML `>` folds lines
into flowing text; `|` preserves line breaks. These forms can also be used
for location descriptions, `setting`, `event-check` and `emit-text`.

### B. Arbitrarily nested groups

```yaml
runtime-fragments:
  "The character explores the lighthouse":
    "The character examines the stairs":
      "The character searches under the steps": >
        Let them discover a loose step bearing a scratched mark.
    "The character examines the lamp": >
      Describe fresh oil and a displaced cover.
```

Only leaves become fragments. The complete key path forms the search text,
with its components joined by line breaks. Group headings are part of the
search context, not automatically emitted text.

### C. `emit-text` and inherited text

The following metadata examples require a top-level `flags` or `signals`
field. `flags: {}` is enough when no flags are needed.

```yaml
flags: {}
runtime-fragments:
  "The character explores the lighthouse":
    emit-text: Keep the wind, damp and cramped tower in view.
    "The character examines the lamp":
      emit-text: The cover was moved recently.
    "The character searches under the stairs": A letter is tucked behind a loose step.
```

Each leaf receives its ancestors' text plus its own, ordered from outermost to
innermost and joined by line breaks. A group with children is not additionally
created as its own fragment. A node containing only `emit-text` is a valid leaf.

### D. Flag and location conditions with AND

```yaml
flags: {nachricht-gefunden: false}
runtime-fragments:
  "The character examines the letter":
    condition: {nachricht-gefunden: true}
    emit-text: Recall the departure time already deciphered.
```

The condition filters fragment eligibility. It does not set flags. It contains
declared flags with `true` or `false` and/or `location` with a location
name, for example `condition: {location: Leuchtturm, nachricht-gefunden: true}`.
All entries must match (AND). Descendants inherit their ancestors' conditions;
AND also applies across nested groups. Contradictory conditions make a fragment
unreachable.

`location` compares the current `world-state.location` exactly, including
case. The name must be nonempty and declared in a nonempty `locations`
catalog; without a catalog any nonempty name is allowed. A location condition
does not require a flag of the same name. The value type distinguishes the two:
`condition: {location: Leuchtturm}` checks location, while
`condition: {location: true}` or `false` checks a Boolean flag named
`location` declared under `flags`. Without that flag declaration the Boolean
form is invalid. Location conditions apply wherever conditions are allowed:
signals, direct rules, named fragments, inherited conditions and `then`
branches. `else` applies only if no preceding sibling branch matches all its
flag and location conditions. Conditions are checked together at the relevant
evaluation point; signal checks use the states described in section 6. A
location condition does not itself cause a location change.

For location-only fragment rules without signals, top-level `flags: {}`
enables metadata syntax (see section H):

```yaml
flags: {}
runtime-fragments:
  condition:
    - location: Leuchtturm
      then: {emit-text: Describe the wind in the tower.}
    - else: {emit-text: The coast remains in the distance.}
```

### E. Conditional branches with `then`

```yaml
flags: {nachricht-gefunden: false, turm-besucht: false}
runtime-fragments:
  "The character asks about the keeper":
    emit-text: The harbor master answers cautiously.
    condition:
      - nachricht-gefunden: false
        then:
          "The character asks without knowing about the letter": >
            Point to a possible clue in the lighthouse.
      - nachricht-gefunden: true
        then:
          "The character asks knowing about the letter":
            condition: {turm-besucht: true}
            emit-text: Have the harbor master explain the mentioned departure time.
```

A list-valued `condition` requires at least one conditional branch. Each
branch has at least one flag or location condition (AND) and exactly one
`then`. `then` is a nonempty mapping that may directly contain `emit-text`,
`signal`, `set-flag`, another `condition`, and named children. Nesting is
possible without another situation name. A single text or list as `then` is
invalid.

All matching branches apply independently: when several conditions match, all
corresponding `then` contents are considered. An optional final
`- else: ...` applies only when no conditional sibling branch matches. Like
`then`, `else` contains a nonempty mapping; it has no conditions of its own
and no additional `then`.

```yaml
flags: {nachricht-gefunden: false, turm-besucht: false}
runtime-fragments:
  "The character asks about further leads":
    condition:
      - nachricht-gefunden: true
        turm-besucht: true
        then:
          "The character connects the message and tower clues":
            emit-text: Connect the message with the traces in the tower.
      - turm-besucht: true
        then:
          "The character remembers the stairs":
            emit-text: Recall the loose stair.
      - else:
          "The character asks without a relevant tower clue":
            emit-text: First point to the lighthouse.
```

If both flags are `true`, the first two branches match and `else` does not
apply. If only `turm-besucht` is true, the second branch matches. If
`turm-besucht` is false, neither matches and `else` applies. In this named
fragment, branch contents remain candidates for semantic selection; they are
not all automatically emitted.

Conditions above the list remain in force. Additional ordinary children on the
parent node inherit its context, but no sibling branch condition. Put shared
additional text in `emit-text` on the parent node.

Branch markers `condition`, `then`, `else` and condition entries do not form
part of the search path. Named leaves therefore need distinct complete search
paths, even with opposite conditions. The examples use distinct leaf names.
Direct rule actions without a search path are unaffected.

### F. `signal` on a leaf or group

```yaml
flags: {turm-besucht: false}
signals:
  turm-betreten:
    triggers:
      - set-flag: {turm-besucht: true}
runtime-fragments:
  "The character actually entered the lighthouse":
    signal: turm-betreten
    emit-text: Describe the narrow spiral staircase.
```

`signal` is exactly one nonempty name of a declared signal. On a group, the
reference is inherited by all leaf fragments; nesting can accumulate several
references. A list of signal names in `signal` is not supported.

A node with only `signal` and an optional simple condition is valid: it defines
a selectable event path without additional text. **A selected named fragment
triggers its referenced signals.** Selection with a successful relevance check
is sufficient; no additional event question is needed. Inherited conditions,
`else` exclusions and the signal's own condition must match the flags from
turn start and the current world-state location. A rejected or ineligible
fragment triggers nothing. An `event-check` also allows an independent
automatic check if the signal was not already triggered by a fragment, direct
rule or location.

A selected fragment can also set flags without a separate signal:

```yaml
flags: {friedhelm-bekannt: false}
runtime-fragments:
  "The character meets Friedhelm for the first time":
    condition: {friedhelm-bekannt: false}
    emit-text: Introduce Friedhelm.
    set-flag: {friedhelm-bekannt: true}
```

`set-flag` is a nonempty mapping from previously declared flags to `true` or
`false`. A fragment may exclusively set flags. Assignments execute only on
confirmed selection of an eligible fragment; a mere search candidate sets no
flags. Named descendants inherit their groups' assignments; the last value
wins for repeated assignments. A fragment remains selected for this evaluation
even if its own assignments make its condition false for later evaluations.

### G. Direct rules without semantic search

A `condition` may also appear directly under `runtime-fragments`. A `then`
can immediately emit text, set flags or trigger a declared signal without a
situation name, search text or model check:

```yaml
flags: {nachricht-gefunden: false, turm-besucht: false, spur-erklaert: false}
signals:
  spur-bereit:
    triggers:
      - set-flag: {spur-erklaert: true}
      - emit-text: The next lead points to the witness in the harbor tavern.
runtime-fragments:
  condition:
    - nachricht-gefunden: true
      turm-besucht: true
      spur-erklaert: false
      then:
        emit-text: The character can now connect the message with the tower clues.
        signal: spur-bereit
    - nachricht-gefunden: true
      then:
        condition:
          - turm-besucht: false
            then:
              emit-text: The message gives a reason to visit the tower.
          - else:
              emit-text: Keep the connection between the message and tower in view.
    - else:
        emit-text: The keeper’s message is not yet known.
  "The character asks the sailor about the departure": >
    He saw a small boat at the breakwater at the third bell.
```

Rules are evaluated before embedding search using a shared snapshot of flags
and the current location; fragment eligibility uses these initial values too.
All matching branches apply, including in nested lists. Here both outer
`then` branches apply when the three requirements match; the second contains
another decision. Direct actions require neither matching narrative focus nor
model relevance confirmation. The named sailor hint remains an ordinary
candidate for semantic selection.

A direct `signal` executes the declared signal actions without an event
question to the model. A signal definition's own `condition` must also match
the initial state. Use rule and signal flag and location conditions to define
requirements precisely. A signal without `event-check` is not additionally
checked independently as an event. A `then: {set-flag: {spur-erklaert: true}}`
without text or signal is also a valid direct rule. Root assignments are not
inherited again by named children. Location-entry actions are followed by
assignments from direct rules, then the selected fragment, then remaining
signal actions. In a conflict the last executed assignment wins. There is no
cascade among rules in the same evaluation: a newly set flag only makes a
previously nonmatching branch eligible on a later evaluation.

Direct texts are emitted again on every matching evaluation. In the example,
`spur-erklaert: false` together with the flag assignment prevents repetition
of the first branch. The second has no such protection and can repeat. All
guidance applies to following answers; it does not change visible narration.
The hidden context contains direct rule texts first in declaration/evaluation
order, then triggered signal texts in signal and trigger order, and finally the
semantically selected fragment. Its eligibility is checked against the shared
initial state; later assignments do not change that selection.

A `then` with named children is also allowed. These remain retrieval-based
fragments and inherit the rule's flag and location conditions. Direct
`emit-text`, `signal` and `set-flag` actions without a named path are
processed there and are not inherited again by named children. Within named
fragment groups, the text, signal and flag assignment inheritance in sections
C and F still applies. Direct `emit-text` without a named path is a rule
action; `emit-text` beneath a named fragment is its selection context.

### H. Compatibility rule for metadata names

```yaml
runtime-fragments:
  Leuchtturm:
    condition: The door is locked.
    emit-text: The steps are wet.
    signal: A bell rings.
```

Without top-level `flags` or `signals`, these are three ordinary text leaves
with paths `Leuchtturm / condition`, `Leuchtturm / emit-text` and
`Leuchtturm / signal`. Once either top-level field exists, these names and
`set-flag` are interpreted as metadata, including directly at the top level
under `runtime-fragments`. Other keys remain fragment names. For new games,
use descriptive situation names and declare `flags: {}` if you want metadata
without actual flags.

### I. Empty and invalid fragments

`runtime-fragments: {}` is valid. Every other group needs at least one valid
leaf or suitable metadata of its own. In particular, these are invalid:

- Empty text, numbers, Boolean values and lists as fragment values.
- Empty subgroups and nodes containing only a simple condition.
- Unknown flag or signal references.
- Empty condition mappings, unknown flags and non-Boolean flag target values.
- Empty or nontext location conditions, or unknown locations when a `locations` catalog exists.
- Empty branch lists or empty `then`/`else` mappings.
- Conditional branches without conditions or without `then`.
- Multiple `else` entries, `else` before the end of the list, or `else` without a preceding conditional branch.
- Additional fields beside `else`, or invalid fields in place of flag or location entries in a conditional branch.
- Duplicate complete search paths for named leaves, including across conditional branches.

There is currently no special object form `{fragment: Text}`. It can be
parsed as a nested mapping, but `fragment` then becomes an ordinary additional
search-path segment. Use direct text leaves or `emit-text` to specify the text
of a named fragment.

## 8. Complete game to copy

Save the entire block below as `das-verschwundene-licht.md`:

```markdown
---
initial-situation:
  world-state:
    location: Kai
  setting: |
    You stand on the quay at Nebelhafen. Fog shrouds the breakwater.
    The harbor master asks you to look for the missing keeper.
locations:
  Kai: Harbor square with access to the breakwater and tavern.
  Leuchtturm: Stone tower with stairs and a lantern room at the end of the breakwater.
  Hafenkneipe: Tavern where sailors observe nighttime departures.
flags:
  turm-besucht: false
  nachricht-gefunden: false
  hafenmeisterin-misstrauisch: false
  spur-erklaert: false
signals:
  turm-betreten:
    event-check: >
      The character actually entered the lighthouse during this exchange.
      Intention, viewing from outside or a failed attempt is insufficient.
    condition: {turm-besucht: false}
    triggers:
      - set-flag: {turm-besucht: true}
  nachricht-entdeckt:
    event-check: >
      The character actually found or read the keeper’s message during this
      exchange. Searching alone is insufficient.
    condition: {nachricht-gefunden: false}
    triggers:
      - set-flag: {nachricht-gefunden: true}
      - emit-text: >
          The character now knows the keeper’s message.
          Connect their next questions to the departure at the third bell.
  misstrauen-geweckt:
    event-check: The harbor master actually became suspicious because of the character’s behavior.
    condition: {hafenmeisterin-misstrauisch: false}
    triggers:
      - set-flag: {hafenmeisterin-misstrauisch: true}
  misstrauen-abgebaut:
    event-check: The previously suspicious harbor master actually calmed down because of the character’s behavior.
    condition: {hafenmeisterin-misstrauisch: true}
    triggers:
      - set-flag: {hafenmeisterin-misstrauisch: false}
  spur-bereit:
    triggers:
      - set-flag: {spur-erklaert: true}
      - emit-text: Connect the known departure time with the witness in the harbor tavern.
runtime-fragments:
  condition:
    - nachricht-gefunden: true
      turm-besucht: true
      spur-erklaert: false
      then:
        signal: spur-bereit
    - else:
        emit-text: Give further clues suited to what is known so far.
  "The character looks for a way into the lighthouse": >
    The door is stuck. Mention rust on the lock and an accessible side entrance.
  "The character examines the lighthouse interior":
    emit-text: Keep the wind, damp stone and creaking stairs in view.
    condition:
      - nachricht-gefunden: false
        then:
          "The character searches under the stairs for clues": >
            Let them find the keeper’s message behind a loose step.
      - nachricht-gefunden: true
        then:
          "The character follows the lead from the discovered message": >
            Connect the mentioned departure time to a witness in the harbor tavern.
  "The character speaks with the harbor master":
    condition:
      - hafenmeisterin-misstrauisch: false
        then:
          "The harbor master is willing to talk": She answers factual questions openly.
      - hafenmeisterin-misstrauisch: true
        then:
          "The harbor master is suspicious": >
            Have her answer cautiously; a credible apology can help.
---
# The Missing Light

## Game Master
Lead a mysterious harbor adventure in English.
Answer concisely, describe concrete consequences, and accept plausible solutions.
The human makes the character’s decisions.

## World and Mystery
The Nebelhafen lighthouse has been dark for three nights.
The keeper pursued a group of smugglers and went into hiding.
Her message under the stairs reads: “Ask at the Crooked Mast about the
departure at the third bell.” A sailor in the tavern knows where she is hiding.
The harbor master wants to help but fears a scandal.

## Progress and Ending
Give clues gradually. A suitable search can find the letter even without
a prescribed wording. The character may use creative approaches.
End the adventure when the keeper is found and the dark light is explained.
```

This example combines structured location changes, one-shot progress, a
reversible social state, independent event checks, and a one-shot direct rule
with multiple AND requirements and an `else` fallback. It also includes
inherited and conditional guidance. The direct rule triggers `spur-bereit`;
the other signals check events. The ending remains a narrative rule: there is
no dedicated `ending` engine field or automatic completion trigger.

## 9. Format limits and common mistakes

| Desired behavior or syntax | Currently supported approach / limit |
| --- | --- |
| Multiple requirements | Multiple flag and/or location entries in a `condition` mapping or conditional list entry before `then` mean AND; inherited conditions must also match |
| Alternatives | All matching `condition`/`then` branches apply; optional final `else` only without a match; describe event alternatives as text in a signal |
| `and`, `or`, `not` as YAML operators | No such operators; use multiple conditions for AND, branches for alternatives, `false` for flag negation and branches/`else` for location fallbacks |
| Toggle or count a flag | Only fixed `set-flag` assignments; use a separate signal to reset; no `toggle` or counter action |
| Give an item, teleport, open dialogue | No such structured trigger; describe it narratively |
| Location condition such as `condition: {location: Kai}` | Exact comparison with current `world-state.location`; can be combined with flag conditions |
| Run guidance based on flags and/or location | Direct rules under `runtime-fragments.condition`; named fragments remain semantically selected candidates |
| Inventory, characters, facts as `world-state` fields | Invalid in frontmatter; describe in Markdown, use flags for binary progress |
| Signal with empty `triggers` | Invalid; at least one `set-flag` or `emit-text` action is required |
| `set-flag: {}` | Invalid; a `set-flag` action must assign at least one declared flag |
| Empty `setting` or `emit-text` | Invalid when specified; omit when not needed |
| Unknown top-level YAML fields such as `title` or `inventory` | Ignored by the parser; they do not configure a game system |

Unknown fields inside `initial-situation`, `world-state` and signal
definitions are rejected. In fragment groups, other keys are interpreted as
children instead. A typo can therefore create a new search path. YAML
indentation is part of the format: use spaces and preserve the shown hierarchy.
Quote names and text containing colons or other YAML special characters. Avoid
duplicate mapping keys.

## 10. Testing your game

1. Import the file. Import errors often concern YAML types, unknown references,
   duplicate fragment paths or a starting location outside the catalog.
2. Start a new session and check the introduction, starting location and flags.
3. Test successful events using different natural phrasings. Also check intention,
   failed attempts and mere mentions as negative cases.
4. Check location changes and flag states in the debug panel. For signals,
   `signal_check` results, triggered signals and applied flag assignments are
   stronger evidence than matching narration alone. Separate cards show each
   detected signal, each flag assignment with old and assigned values, and each
   emitted text with its source. Reassignments of the same value remain visible.
   Text cards distinguish signal text, direct flag rules and selected fragments.
5. Check conditional fragments before and after a state change. Note their
   selection for later responses and the absence of same-turn signal cascades.
   Check direct rules without matching narrative focus, multiple matching
   branches, and `else` only without a matching sibling. For `emit-text`
   signals check the next turn's hidden context; for one-shot events check that
   the condition and flag assignment prevent repetition.
6. Reload and resume the saved game. Locations, flags and history should
   persist. Also test a fresh session.

A valid file does not guarantee perfect event detection by every local model.
Clear, broadly phrased event descriptions and distinct search paths make the
game easier to test.

More examples:

- [Complex lighthouse game](../examples/nebelhafen-leuchtturm.md) with nested fragments and
  signal references without explicit event descriptions.
- [The Facility](../examples/the-facility.md), an exploration adventure with
  locations, flags, signals and runtime fragments.

## 11. Basis of this reference

The described forms come from the current parser and runtime:

- [Scenario parser](../web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt)
  and [parser tests](../web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt).
- [File import](../web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioImport.kt).
- [Signal checks](../server/src/main/kotlin/de/aidadventure/server/runtimefragments/SignalChecks.kt)
  and [condition filtering and flag assignments](../server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeSignalState.kt).
- [Fragment selection](../server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolver.kt),
  [world-state evaluation](../server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt)
  and [prompt construction](../server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt).
