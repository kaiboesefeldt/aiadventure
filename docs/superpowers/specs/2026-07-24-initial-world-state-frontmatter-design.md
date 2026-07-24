# Initial WorldState in scenario frontmatter

## Goal

Let a scenario declare the exact `WorldState` with which each newly created
session starts. The state is validated in the browser at scenario import,
persisted as part of the session, and never changes an existing session.

## Frontmatter format

`world-state` is optional. When present, it has this strict shape:

```yaml
world-state:
  entities:
    mira:
      kind: npc
      name: Mira
      summary: Misstrauisch, wartet am Kai.
  facts:
    mira-promised-a-meeting: "true"
```

`entities` maps non-empty entity IDs to entities with non-empty text fields
`kind`, `name`, and `summary`. `facts` maps non-empty fact keys to text
values. Fact values remain arbitrary strings; YAML booleans, numbers, lists,
and objects are invalid values.

Only `entities` and `facts` are valid keys under `world-state`; only `kind`,
`name`, and `summary` are valid keys under an entity. Unknown keys are an
import error so misspellings cannot silently disable gameplay mechanics.

The author cannot set a revision. Parsing always creates
`WorldState(revision = 0, ...)`. If `world-state` is absent, the parsed start
state is the existing empty `WorldState()`.

## Data flow

`ScenarioFrontmatter.parse()` continues to strip frontmatter for prompt input
and to return runtime fragments. It additionally returns a validated
`initialWorldState` using the existing browser `WorldState` and `WorldEntity`
models. Invalid world-state data makes parsing fail, so `ScenarioImport`
rejects the complete scenario exactly as it already does for invalid
frontmatter.

When the player creates a new session, the client parses the saved scenario
markdown and initializes `GameSession.worldState` from `initialWorldState`.
That session is then persisted through the existing IndexedDB serialization.
The server receives the session's current state on prefill and turn requests;
it does not parse frontmatter and needs no change.

Existing sessions retain their persisted state. Updating or reimporting a
scenario changes only sessions created afterward.

## Error handling

Import rejects a scenario when `world-state`, `entities`, `facts`, or an
entity has the wrong YAML shape; an ID/key is empty; a required entity field
is missing, empty, or non-textual; a fact value is non-textual; or a strict
mapping contains an unknown key. The UI uses its existing invalid-frontmatter
import error rather than partially accepting the scenario.

## Verification

Browser tests cover parsing a populated state, an omitted state, revision zero,
all strict validation boundaries, and creation/persistence of a session with
the parsed state. Existing session-loading coverage demonstrates that the
persisted state continues to be used after reload. The change does not alter
prompt construction, turn flow, prefill, or cache contracts.
