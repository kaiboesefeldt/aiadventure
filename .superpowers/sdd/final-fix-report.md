# Final fix report: same-origin browser API paths

## Scope

Implemented the corrective change from `final-fix-brief.md` only:

- `web/src/jsMain/kotlin/de/aidadventure/web/api/AdventureApi.kt`
- `web/src/jsTest/kotlin/de/aidadventure/web/api/AdventureApiUrlTest.kt`

`AdventureApiClient.DEFAULT_BASE_URL` now defaults to `""`, preserving the
existing URL interpolation and yielding `/api/models` and `/api/turn` for the
browser client served by the API. A supplied non-empty `baseUrl` is unchanged.

## Root-cause evidence

The packaged UI is served from `http://127.0.0.1:8080/`, but
`AdventureApiClient.DEFAULT_BASE_URL` was `http://localhost:8080`. With the
development CORS origin removed, that default made browser calls cross-origin
instead of same-origin.

## RED

Added `AdventureApiUrlTest` with the required test name:

```kotlin
@Test
fun `default API base URL is same-origin`() {
    assertEquals("", AdventureApiClient.DEFAULT_BASE_URL)
}
```

Command:

```text
./gradlew :web:browserTest --tests de.aidadventure.web.api.AdventureApiUrlTest
```

Result: failed as expected. The generated browser test result recorded one
failure with:

```text
AssertionError: Expected <>, actual <http://localhost:8080>.
```

## GREEN

Changed only `DEFAULT_BASE_URL` from `"http://localhost:8080"` to `""`.

Command:

```text
./gradlew :web:browserTest --tests de.aidadventure.web.api.AdventureApiUrlTest
```

Result: `BUILD SUCCESSFUL in 17s`; the focused browser regression test passed.

## Required verification

```text
./gradlew check
```

Result: `BUILD SUCCESSFUL in 1s` (27 actionable tasks; 1 executed,
26 up-to-date).

```text
./gradlew :web:browserProductionWebpack
```

Result: `BUILD SUCCESSFUL in 1s` (16 actionable tasks; 1 executed,
15 up-to-date).

```text
! rg -n "localhost:8080" web/build/kotlin-webpack/js/productionExecutable
```

Result: exit 0 with no output, confirming that the generated production bundle
contains no `localhost:8080` string.

## Notes

The Gradle runs emitted existing Kotlin JS deprecation warnings and JVM
`hsperfdata` lock warnings; neither caused a task failure. No server routes,
SSE handling, prompt construction, Ollama configuration, persistence, README,
or design/plan documents were modified.
