# Stream Ollama tokens design

## Goal

Show the game master's response in the browser as Ollama generates it, while
keeping the server stateless and retaining only completed responses in the
browser's IndexedDB session.

## Transport

`POST /api/turn` remains the single turn endpoint but no longer returns a
single JSON object. Its successful response uses `text/event-stream` and emits
three Server-Sent Event types:

- `token`: JSON data with one generated text fragment.
- `done`: JSON data with the final safe turn metrics.
- `error`: JSON data with a client-safe error code and message if Ollama fails
  after streaming has begun.

Validation, model discovery, and unavailable-model failures happen before the
response starts and remain ordinary JSON HTTP errors. The browser sends the
same JSON `TurnRequest` body as before.

## Server flow

The server keeps validation, model-catalog lookup, request-ID logging, and the
deterministic prompt order unchanged. `LangChain4jOllamaGateway` uses
`OllamaStreamingChatModel` with the existing `think(false)` and `keep_alive`
parameters. It forwards every partial text callback to the HTTP stream, then
forwards final metrics in `done`.

The gateway never forwards thinking callbacks. Operational failures are logged
with only the request ID, operation, result, and exception class; event payloads
do not expose scenario, history, actions, or model text beyond the generated
token stream requested by the browser.

## Browser flow

The browser calls the endpoint with `fetch`, incrementally decodes the response
body, and parses its SSE frames. On every `token`, it updates one in-memory
game-master message so it appears immediately in the play view. It saves the
player action before the request as today, then saves the completed game-master
message only after `done`.

On an `error` event or interrupted stream, the temporary game-master message is
removed from the in-memory session and the persisted player action remains. The
existing safe client error presentation remains in use. Sending stays disabled
until the stream completes or fails.

## Testing

- Server route tests verify token, done, and in-stream error SSE frames, and
  preserve the existing pre-stream validation/error HTTP behavior.
- Gateway tests verify streaming configuration keeps `think(false)`.
- Browser tests verify incremental message updates, final persistence only on
  `done`, and removal of the temporary message after stream failure.
- `./gradlew check` remains the full build and test verification.
