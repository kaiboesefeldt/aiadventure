# Runtime Fragment Re-Ranking Design

## Goal

Before warming a retrieved runtime fragment for the next player action, use the
local model to score whether that fragment's key (`matchText`) fits the just
completed game situation. Only a score from 5 through 9 admits the fragment.

## Scope

The change is confined to the server's post-turn background work. The browser
continues to receive only the game-master token stream, the existing hidden
fragment event for the current turn, and the final metrics event. It receives
neither the candidate key nor the score nor the analysis prompt.

## Flow

1. After a successful streamed turn, derive `completedHistory` from the
   resolved history, player action, and generated game-master text.
2. Retrieve the single top `RuntimeFragmentDefinition` from that history.
3. On the retained coordinator slot, append the definition's `matchText` as a
   temporary system history entry and append a fixed user analysis prompt. The
   prompt instructs the model to answer with exactly one ASCII digit `0`–`9`,
   where 0 means no fit and 9 means perfect fit.
4. Request one completion token and parse only an exact, trimmed single digit.
   An invalid response, a score below 5, or any analysis failure rejects the
   candidate.
5. Construct a fresh canonical warmup prompt after the analysis: it contains
   `completedHistory` plus the candidate's `fragment` only for accepted
   candidates. It never contains the temporary key or analysis prompt. The
   warmup uses the same session ID and retained llama.cpp slot.
6. Later browser input prefills remain queued behind this one background
   operation and use the retained slot. Thus the cache is filled only after
   analysis and canonical cleanup have completed.

## Architecture

`RuntimeFragmentResolver` returns a complete runtime-fragment definition so
the caller can separately use its retrieval key and injected fragment text.
`SessionPrefillCoordinator` owns the background re-ranking operation and the
slot for its full lifetime. `PrefillGateway` gains a non-streaming relevance
completion that returns model text; `LlamaCppGateway` limits it to one
predicted token. `PromptFactory` constructs the temporary score prompt with
the usual deterministic prefix: instruction, scenario, history, analysis
action.

## Failure Handling and Privacy

All model output is untrusted. Scores are accepted only when their complete
trimmed response matches one digit in `0`–`9`; the threshold is `>= 5`.
Failures are logged with request ID, operation name, and exception class only.
No scenario, history, candidate key, candidate fragment, score, or model text
is logged. A failed re-ranking must not fail or delay the already completed SSE
turn.

## Invariants

- The game-master prefix remains deterministic: instruction, original
  scenario, history, new action.
- Analysis-only history is never persisted, emitted, or used in the canonical
  warmup prompt.
- The analysis and its following warmup use the same session ID and retained
  llama.cpp slot.
- The cache-contract tests for new, resumed, and following turns retain an
  exact prompt-prefix relationship and at least one warm slot.
- A fragment remains hidden from the ordinary transcript; only the previously
  established current-turn fragment SSE event is retained.

## Verification

Unit-test exact score parsing and the threshold. Add route/coordinator tests
for accepted, rejected, malformed, and failed scores; assert that the
temporary prompt contains key plus analysis instruction, while the eventual
warmup contains either the fragment or neither temporary value. Extend the
cache-contract integration tests to prove the following turn reuses the
canonical warmup cache on the retained slot. Run `./gradlew check`.
