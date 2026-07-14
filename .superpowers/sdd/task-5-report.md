# Task 5 report

Implemented and committed the browser game flow in `5dbd463 feat(web): add persistent adventure game flow`.

- Added a Ktor JS API client, serializable turn models, safe API errors, and a `TurnRequestMapper` that preserves session history before the new action.
- Wired IndexedDB repositories and the API client through Koin at browser startup.
- Added library import/listing, scenario detail/model/session selection, and a text-only play view with persisted messages, retry-safe failure state, pending-send protection, and optional response metrics.
- Added the requested mapper test. It was observed failing before the mapper existed, then passing after implementation.

Verification:

- `./gradlew :web:browserTest --tests '*TurnRequestMapperTest'` (the brief's `:web:jsTest` task is not present in this Kotlin/JS project)
- `GRADLE_OPTS='-Xmx2g' ./gradlew :web:browserTest :web:compileKotlinJs`
- `GRADLE_OPTS='-Xmx2g' ./gradlew check`

The temporary 2 GiB heap was needed once because `compileTestDevelopmentExecutableKotlinJs` exhausted the default compiler heap; the rerun passed.

## Review follow-up

Fixed the two important review findings:

- `DetailView` and `PlayView` are now separate `FC` components rendered with `create`, so `AdventureApp` has a fixed hook sequence while switching between library, detail, and play views.
- `TurnSubmission` updates the in-memory session with the player action before its first persistence attempt, then persists it before calling the API. The play view catches failures from either persistence or the API and resets `pending` in `finally`; a failed initial save does not issue an untracked API turn.
- Added `TurnSubmissionTest` covering a failed initial session save: the player action remains in the UI session and no API request is made.

Fresh verification:

- `GRADLE_OPTS='-Xmx3g' ./gradlew -Dkotlin.daemon.jvmargs='-Xmx3g' :web:browserTest --tests '*TurnSubmissionTest'`
- `GRADLE_OPTS='-Xmx3g' ./gradlew -Dkotlin.daemon.jvmargs='-Xmx3g' :web:browserTest :web:compileKotlinJs`
- `GRADLE_OPTS='-Xmx3g' ./gradlew -Dkotlin.daemon.jvmargs='-Xmx3g' check`
