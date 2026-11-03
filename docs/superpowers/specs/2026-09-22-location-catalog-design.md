# Canonical Location Catalog

## Goal

A scenario names playable locations in YAML frontmatter. Location resolution stores only these canonical names. Free or combined model responses such as `Turm Gegenstand` have no effect.

## Frontmatter

```yaml
world-state:
  location: Kai
locations:
  Kai: "Harbor loading area with bollards and ropes."
  Leuchtturm: "Old stone tower at the end of the pier."
  Hafenkneipe: "Waterfront inn frequented by sailors."
```

`world-state` is the initial state of a new session. `locations` is optional; when present, it is a nonempty map of unique nonempty keys to string descriptions. Descriptions may be empty. A specified starting location must be among the map keys. Without a catalog, location resolution fails closed and leaves the state unchanged.

The browser continues to remove frontmatter from scenario text and passes the catalog in turn, prefill and warmup requests. All server-generated prompts receive the same stably sorted catalog. Each entry contains its key and, if nonempty, its description. This applies to player turns, warmups, recaps, world-state resolution and fragment evaluation.

## Resolution

After the visible turn, the primary server generates a structured response for the current location. The schema permits only catalog keys and the previous location. The instruction asks for the location where the character actually is after the response; refused, planned or unclear movement retains the previous location. Kotlin accepts a changed name only if it is a catalog key and appears as a complete text element in the game master's response.

The free `movement` plus `location` chain is removed. Narrative focus, the retained slot and the subsequent canonical warmup remain unchanged.

## Tests

Tests cover catalog parsing, starting location membership, browser-to-server forwarding, the location enum, `Kai` to `Leuchtturm`, and retention when location mentions are unconfirmed. The cache contract tests remain.
