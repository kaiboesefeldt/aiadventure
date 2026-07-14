# Native browser stream for turns

## Problem

The Ktor JavaScript client receives the `/api/turn` response only after its body
has completed. The server and browser `fetch` stream each SSE chunk immediately,
so this client boundary prevents progressive rendering.

## Design

`AdventureApiClient.play` uses the browser `fetch` API directly. It sends the
existing JSON request, checks the HTTP response, then reads
`response.body.getReader()` one chunk at a time. A `TextDecoder` preserves
UTF-8 text across chunk boundaries; the existing `SseFrameDecoder` turns the
decoded text into token, completion, and error events.

The Ktor JavaScript client remains responsible for the non-streaming
`/api/models` request. No server contract, session persistence, prompt, or UI
contract changes.

## Error handling

Non-successful responses use their JSON error body when available. A response
without a readable body, an incomplete stream, or an unreadable SSE event uses
the existing `AdventureApiException` path.

## Verification

Unit tests cover incremental UTF-8 decoding and delivery of token frames before
the completion frame. A Chromium end-to-end probe records multiple rendered
game-master updates before the request completes. `./gradlew check` remains the
full build gate.
