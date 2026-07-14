# Flush streamed token rendering design

## Goal

Render each game-master text fragment visibly as it arrives, instead of letting
React batch all stream-driven session updates until the response completes.

## Change

`PlayView` wraps only its streamed-session callback in React DOM's `flushSync`.
The callback continues to receive the transient session built by
`TurnSubmission`; `flushSync` commits the corresponding parent view update
before the next incoming token is processed.

The initial player-action update and the final completed-session update retain
their current normal React scheduling. Server streaming, SSE parsing, IndexedDB
persistence, prompt construction, and error handling are unchanged.

## Verification

A focused browser test verifies that streamed session updates pass through the
synchronous render boundary. The full Gradle check verifies both modules after
the change. A manual browser smoke test uses a multi-token game-master reply to
confirm visible incremental text.
