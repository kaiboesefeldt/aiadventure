# Vector Query TRACE Logging Design

## Goal

Expose the selected runtime-fragment vector-query result at TRACE level with
its fragment text and Lucene similarity, without changing retrieval behavior.

## Design

`RuntimeFragmentIndexRegistry.query` logs at the point where it still owns the
complete `VectorStore.QueryResult`. When TRACE is enabled and a top result
exists, it emits one escaped diagnostic record containing that result's
`runtimeFragment` field and `similarity`. No record is emitted for an empty
result set.

The registry continues returning the same `RuntimeFragmentDefinition`; the
resolver, embedding flow, Lucene query, selection order, prompt construction,
and cache lifecycle are unchanged. A small injectable trace sink makes the
behavior testable without capturing SLF4J output.

## Constraints and Verification

Logging must be lazy when TRACE is disabled and escape the fragment text. Unit
tests prove selected fragment plus similarity are sent to the sink, no-hit
queries produce no trace record, and disabled tracing performs no content-log
work. Run `./gradlew :server:test` and final `./gradlew check`.
