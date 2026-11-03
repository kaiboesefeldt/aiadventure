# Narrative Focus in WorldState

## Goal

In addition to the canonical location, the persisted WorldState gains one narrative focus. It keeps the element on which the story rests after the last turn visible to the primary model: for example, a place, object, puzzle, danger, relationship or undertaking. It is a soft narrative aid, not another hard world fact.

## Data contract

`WorldState` has `location: String` and `narrativeFocus: String`, both defaulting to empty. The llama.cpp schema recommends at most 160 characters for a new nonempty focus, but Kotlin accepts any valid complete model response regardless of length. The fields are independent: an action can set or continue a focus even when the location remains unknown. The browser persists them like the location and migrates the former JSON names `activeThread` and `unresolvedSituation` when reading. Scenario frontmatter continues to initialize only `location` and cannot specify a focus.

The primary snapshot renders the location unchanged as an authoritative fact and the nonempty focus separately as `NARRATIVE FOCUS`. Warmup and the next turn receive the same accepted snapshot so warmup remains an exact prefix of the turn prompt.

## Secondary resolution

After the visible turn, location resolution first returns an intermediate state; even with an empty previous location it does not return early from the resolver. Exactly one further short secondary call follows, using the previous focus, last player action and last GM response. Its schema response is only `{"narrativeFocus":"..."}`; the empty string means no narrative focus exists.

The fixed prompt retrospectively updates the focus from the previous focus, player action and GM response. It requests a short phrase for the one currently central story element; that may describe exploration alone and need not be a conflict or task. The 160 characters limit only the requested generation, not Kotlin's acceptance. On schema, transport or validation errors, the previous focus remains. A successful focus call updates it even when location resolution found no new location. Local TRACE mode logs complete secondary responses for diagnosis.

## Flow and tests

The calls remain sequential: location decision and, if necessary, location extraction, followed by narrative focus. Only the resulting state enters SSE `done` and the subsequent warmup. Tests check the schema limit, acceptance, error fallback, snapshot rendering, browser serialization and legacy value migration, and the cache prefix relationship.
