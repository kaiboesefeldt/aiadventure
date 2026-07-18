# Runtime vector fragments design

## Goal

Allow a Markdown game template to define retrieval-triggered runtime fragments.
For each turn after the first, the server embeds the previous visible player
message plus the previous game-master answer, retrieves exactly one matching
fragment, and injects it as hidden game context before the new player action.
The resulting prompt must continue to reuse the llama.cpp cache through the
previous game-master answer.

## Template and browser persistence

A template may start with a YAML frontmatter document. The optional
`runtime-fragments` mapping has a match text as its key and a non-empty
`fragment` value:

```yaml
---
runtime-fragments:
  "Du Mistkerl, du alter Depp, du Arschloch!":
    fragment: "Weise den User darauf hin, dass er seine Zunge zügeln soll"
  "Der Leuchtturm ist so schön im Abendlicht":
    fragment: "Mira erzählt eine alte Geschichte über den Leuchtturm"
---
```

The browser stores the complete, unmodified imported template, including its
frontmatter, in the existing IndexedDB `scenarios` store. It is the source of
truth when starting another game later. No frontmatter is equivalent to an
empty `runtime-fragments` mapping.

At import and when preparing a request, a frontend parser produces an
ephemeral parsed representation:

- original template Markdown, retained unchanged for IndexedDB;
- scenario Markdown with the complete YAML frontmatter removed;
- ordered runtime-fragment definitions containing match text and fragment
  text.

Malformed YAML, a non-mapping `runtime-fragments` value, blank match text,
missing `fragment`, non-string values, or blank fragment text rejects the
import with a clear local error. The stripped frontmatter is never part of a
model prompt.

## Retrieval and index lifecycle

Warmup, prefill, and turn requests carry the stripped scenario Markdown and
the parsed fragment definitions. The server owns a transient,
memory-bounded, TTL-cleaned `RuntimeFragmentIndexRegistry`, keyed by a stable
content fingerprint of the parsed definitions. It uses the existing local
ONNX embedding service and Lucene vector store.

On the first request for a fingerprint, the registry embeds each match text
and indexes it with a stable content-derived document ID. Later requests reuse
that transient index. It stores no game sessions or durable scenario data and
can always rebuild an entry from a request after eviction or restart.

For a request with a complete preceding visible pair, the resolver embeds
exactly this concatenation in a documented deterministic format:

```text
<last player message>\n<last game-master message>
```

It queries the scenario index and selects exactly the top result. Ties use the
stable document ID as a deterministic secondary order. There is no score
threshold and no multi-result injection in this first version. The
resolver ignores hidden runtime-fragment history entries when finding the last
visible pair. A first turn has no preceding pair and injects no fragment.

If embedding, index construction, or querying fails, the turn proceeds without
a fragment. The server emits a warning containing only request ID, operation,
and failure class; it never logs scenario content, match text, fragment text,
history, player action, model output, or a raw session ID.

## Prompt, streaming, and persistence contract

`runtime-fragment` is a distinct hidden history role. The server translates it
only to a system message; the UI never renders it as a chat message.

For every turn after the first, the logical conversation order is:

```text
... -> previous player -> previous game master
    -> runtime fragment -> current player -> current game master
```

The resolver selects the fragment before llama.cpp is called. `PromptFactory`
therefore constructs:

1. fixed game-master instruction;
2. stripped scenario Markdown;
3. chronological persisted history, including hidden fragment entries as
   system messages;
4. the newly selected hidden system fragment, if any;
5. the current player action.

This preserves the previously warm prompt prefix through the prior game-master
answer. The only new suffix is the selected fragment and current action.
Prefill and its matching turn must resolve the same fragment and use the same
session ID, coordinator, and llama.cpp slot. A successful later turn leaves a
history whose next request has the preceding conversation as an exact prefix.

The turn SSE stream emits an internal `fragment` event, if selected, before
any token events. The browser inserts it immediately before the pending current
player entry in its in-memory transcript. On successful completion it persists
that exact chronological ordering to IndexedDB; it never displays the entry.
Normal token, done, and error behavior remains unchanged for visible messages.

## Components

- Frontend `ScenarioFrontmatterParser`: validates and strips frontmatter while
  preserving original imported Markdown for IndexedDB.
- Shared API DTOs: carry runtime-fragment definitions on warmup, prefill, and
  turn requests; accept the `runtime-fragment` history role.
- Server `RuntimeFragmentIndexRegistry`: builds and bounds reconstructible
  Lucene indexes from definitions.
- Server `RuntimeFragmentResolver`: extracts the last visible pair, retrieves
  top 1, and enriches prompt work consistently for warmup/prefill/turn.
- `PromptFactory`: maps persisted or newly selected runtime fragments to system
  messages in the specified order.
- Turn SSE adapter and web turn submission: transmit, position, persist, and
  hide the internal fragment event.

## Verification

Automated tests must cover:

- absent, valid, and invalid frontmatter; the raw template remains in IndexedDB
  while stripped Markdown alone reaches the prompt;
- index construction from match text, deterministic top-1 selection, and no
  fragment on a first turn;
- resolver input made from the latest visible player/game-master pair, ignoring
  hidden fragments;
- fallback without a fragment and privacy-safe warning behavior when retrieval
  fails;
- SSE fragment-before-token ordering, transcript insertion before the current
  player message, IndexedDB persistence, and no rendering of the hidden role;
- equal resolver output for prefill and turn, and the required prompt order;
- exact prompt-prefix and retained-slot cache-contract cases for new games,
  resumed games, and a following turn; and
- a live llama.cpp proof that prefill then turn use one slot and report
  `tokens_cached` / `tokens_evaluated` cache metrics.

The final repository verification remains `./gradlew check` without excluding
Kotlin/JS dependency installation tasks.
