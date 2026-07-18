# Lighthouse Example Runtime Fragments Design

## Goal

Extend the bundled Nebelhafen example with realistic runtime-fragment frontmatter
that demonstrates broad semantic retrieval without changing gameplay code.

## Content shape

Add exactly 40 runtime fragments to examples/test-game.md:

- 10 about the lighthouse and its immediate surroundings;
- 10 about the secret signal light and smuggling clues;
- 10 about the harbor master and her motives; and
- 10 about the tavern, rumours, and supporting characters.

Every mapping key is a natural-language situation that can be semantically
matched against a preceding player action and game-master answer. Keys are
specific enough to separate nearby story beats. Every fragment value is one
short, concrete instruction to the game master: a sensory detail, clue,
reaction, complication, or next lead.

## Constraints

- The YAML frontmatter is valid runtime-fragments frontmatter and precedes the
  existing Markdown unchanged.
- Fragment text remains hidden from the player by the runtime-fragment feature.
- The examples deepen the existing setting only; they introduce no unrelated
  locations, factions, or objectives.
- The fragments cover all four requested story areas and avoid generic
  restatements of the scenario.

## Verification

Parse the example through ScenarioFrontmatter and assert 40 definitions; run
the existing web frontmatter parser/import tests and check the worktree diff.

