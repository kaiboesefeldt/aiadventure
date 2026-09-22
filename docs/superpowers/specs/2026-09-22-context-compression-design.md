# Automatic context compression

The implementation follows the accepted context-epoch design in `docs/gameplay-architecture-roadmap.md`.

## Behavior

Keep the full original transcript in browser IndexedDB. Persist a versioned active checkpoint and an optional pending checkpoint alongside it. Server requests carry the original transcript and these artifacts, allowing a restarted stateless server to validate coverage and rebuild the exact prompt. An active checkpoint replaces only its covered history with a narrative recap; retain the original instruction/scenario prefix and current world-state placement. Recaps are untrusted narrative text, not authoritative world state.

Count the actual templated prompt using llama.cpp tokenization and read the retained slot's context capacity. Reserve bounded generation space. At the soft threshold, create a Primary recap on the existing slot after the visible turn. Keep the old epoch until three more completed raw turns, unless capacity requires an earlier switch. Switch active checkpoint atomically and warm the new prefix on the same retained slot. Subsequent warmup, prefill, relevance and visible turns must use the same representation. Primary maintenance may delay completion of a turn; the player may continue typing during it. Session shutdown can cancel maintenance, ordinary typing and quick sends cannot.

Malformed, empty, stale or non-reducing recaps never replace an active checkpoint. Below the hard limit, failed optional maintenance preserves the old epoch. If the actual prompt plus required output cannot fit, return a specific context-limit error rather than silent truncation or generic service-unavailable. Existing oversized sessions may require a larger model context if no complete safe recap can be made. The obsolete 200-entry rejection must no longer stop otherwise valid long compressed games; retain a generous bounded request safety limit.

## Wire and persistence

All three request DTOs, GameSession and StreamDone carry `contextCompression: ContextCompression = ContextCompression()`.

```kotlin
@Serializable data class ContextCompression(
    val epoch: Long = 0,
    val active: ContextCheckpoint? = null,
    val pending: ContextCheckpoint? = null,
)
@Serializable data class ContextCheckpoint(
    val recap: String,
    val coversThroughTurn: Int,
    val coversThroughEntry: Int,
    val sourceFingerprint: String,
    val stateRevision: Long,
    val modelId: String,
)
```

`coversThroughEntry` is an exclusive raw-history boundary, including hidden runtime fragments. Fingerprints bind session/scenario/covered source; revisions identify the state at recap generation, not the evolving current state. Default fields load old saves without migration. Browser saves returned compression together with the completed visible turn and state. Original raw history remains available for validating artifacts and rebuilding them.

## Verification

Test source validation, repeated compression, soft/hard budgets, retained tail, recap failure, exact prompt prefixes and retained slots for new/resumed/following compressed turns, maintenance quick sends and shutdown, and browser save/reload and wire propagation. Preserve existing cache-contract tests. Final acceptance is `./gradlew check` including Kotlin/JS dependency installation, plus diff review. Do not claim real model cache reuse from fake metrics; only real llama.cpp metrics prove live reuse.
