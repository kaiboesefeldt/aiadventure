# Bounded Runtime-Fragment Embeddings Design

## Goal

Runtime-fragment retrieval must continue when either the latest player action
or the preceding game-master response exceeds the embedding model's supported
input length.  The retrieval query must give both messages equal semantic
weight, regardless of their respective lengths.

## Scope

This change affects only construction of the vector used to query the
runtime-fragment Lucene index.  Scenario-defined `matchText` values continue
to be embedded individually when an index is prepared.  The prompt, persisted
browser transcript, resolver timing, fragment selection, and llama.cpp cache
contract remain unchanged.

## Bounded Component Embedding

Introduce a small server-side component that turns an arbitrary text into one
embedding vector using the existing `EmbeddingService`.

- A model-specific, conservative `maxEmbeddingCharacters` value is initially
  `512` for the configured runtime tokenizer.  It is an
  explicit application limit, not a value inferred from the ONNX graph.
- Text at or below the limit is embedded once through `EmbeddingService`.
- Longer text is split near its midpoint at a word boundary.  If no word
  boundary exists, it splits at the midpoint so recursion always terminates.
- Both halves are embedded recursively.  Their vectors are combined with a
  weighted arithmetic mean using each half's character count.  Nested splits
  use the same rule, so the final component vector represents all terminal
  chunks in proportion to their original character counts.
- A zero-length text is not sent to the embedding model.  The resolver's
  existing message validation/selection determines whether an empty visible
  message can arise; the bounded component must reject it deterministically
  rather than divide by zero.

The character cap is intentionally simple.  Character count is not an exact
token count, but the lower 4,000-character application limit is the agreed
conservative guard for the configured model.  Embedding errors continue to
propagate to the existing graceful resolver fallback; this change does not
classify or retry model errors by parsing their messages.

## Query Construction

The resolver continues to select the most recent visible player message and
most recent visible game-master message, ignoring `runtime-fragment` history.
It embeds each selected message separately with the bounded component, then
calculates:

```text
query = normalize((playerVector + gameMasterVector) / 2)
```

`normalize` is L2 normalization.  The equal outer mean deliberately makes the
player action and game-master response equal contributors; the internal
character weighting applies only within a single oversized message.  The
normalized query is passed unchanged to the existing Lucene cosine index.

## Failure Handling and Privacy

The existing route-level behavior remains: a failure while resolving a runtime
fragment emits only the safe request-ID-based warning and the game continues
without a fragment.  The bounded embedding component must not log message
text, chunks, or vectors.

## Verification

Focused unit tests cover:

1. Short input makes exactly one embedding call.
2. Oversized input splits at a word boundary, recursively embeds its leaves,
   and returns the character-weighted vector mean.
3. A no-whitespace input still terminates through a midpoint split.
4. The resolver embeds player and game-master messages independently, gives
   their resulting vectors equal weight, normalizes the final vector, and
   passes that vector to the registry.
5. An embedding failure still propagates to the existing graceful resolver
   fallback without exposing game text in logs.

The final gate is the focused server test set followed by `./gradlew check`.
