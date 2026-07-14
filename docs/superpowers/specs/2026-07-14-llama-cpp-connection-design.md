# llama.cpp connection design

## Goal

Allow the adventure game to use a local `llama.cpp`/`llama-server`
instance as an alternative to Ollama. The model-selection view must offer a
port field and a two-stage connection/start button, while existing Ollama
sessions continue to work unchanged.

## Scope

- `llama.cpp` is always addressed as `http://localhost:<port>`.
- The default port is `8081`.
- The first click on the `llama.cpp` button checks the connection by reading
  the model from the OpenAI-compatible `/v1/models` endpoint.
- After a successful check, the button displays the discovered model name and
  the port field is disabled.
- A second click starts the game with that provider and model.
- A saved session stores its provider, model name, and `llama.cpp` port.
- When a saved `llama.cpp` session is opened again, its port is editable until
  the connection check succeeds. This permits correcting a changed port.
- No remote host configuration, multiple `llama.cpp` models, or persistent
  server-side session data is introduced.

## Architecture

The server remains stateless and owns all model calls. The client sends the
provider configuration with each turn. A provider-aware gateway boundary
selects either the existing Ollama implementation or a new raw HTTP
`llama.cpp` implementation. The prompt factory remains shared, so the stable
prompt prefix remains:

1. fixed game-master instruction
2. original scenario Markdown
3. previous conversation
4. new player action

The browser does not call `llama.cpp` directly. This avoids making browser CORS
behavior part of the feature and keeps operational failure handling and
request-ID logging in the server.

## API contract

### Model discovery

Keep `GET /api/models` as the Ollama model discovery endpoint. Add a separate
endpoint for `llama.cpp`:

```text
GET /api/llama/models?port=8081
```

The server validates the port, requests
`http://localhost:<port>/v1/models`, extracts the first model ID, and returns
that model in the existing model-list response shape. An unavailable service,
invalid response, or empty model list returns the existing client-safe
unavailable error shape with HTTP 503. The server logs only request ID,
operation, result, and technical exception class.

### Turn requests

Extend `TurnRequest` with a provider and optional `llamaCppPort`:

```kotlin
enum class ModelProvider { OLLAMA, LLAMA_CPP }

@Serializable
data class TurnRequest(
    val modelId: String,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
    val provider: ModelProvider = ModelProvider.OLLAMA,
    val llamaCppPort: Int? = null,
)
```

Ollama requests use the existing model catalog and gateway. `llama.cpp`
requests require a valid port and use the requested localhost endpoint. Model
validation for `llama.cpp` is performed against the configured endpoint before
the streaming response begins.

The `llama.cpp` request uses the OpenAI-compatible chat-completions contract,
with the shared prompt messages translated to OpenAI roles. Streaming maps
SSE content deltas to the existing `token` events and emits the existing
`done`/`error` events. LLM output remains untrusted text.

## Browser persistence and flow

Extend `GameSession` with provider and nullable `llamaCppPort`, using Ollama as
the default when decoding older records. The turn mapper copies these values
into `TurnRequest`.

The scenario detail view contains the existing Ollama model list and a
separate `llama.cpp` control:

1. Render port input with value `8081` for a new connection or the saved
   session port when reopening a `llama.cpp` session.
2. Keep the port input enabled and the button labelled as a connection action.
3. On the first click, call the server model-discovery endpoint.
4. On success, retain the discovered model, show its name on the button, and
   disable the port input.
5. On the next click, create or resume the session and enter the play view.
6. On failure, keep the port editable and show a client-safe error.

Ollama model buttons retain their current one-click start behavior. A saved
Ollama session retains its direct resume behavior. A saved `llama.cpp` session
returns through the connection control so its port can be corrected before
play starts.

## Validation and compatibility

- Ports must be integers between `1` and `65535`.
- `llama.cpp` discovery and turns must reject missing or invalid ports before
  making an outbound request.
- Existing JSON requests without the new fields decode as Ollama requests.
- Existing IndexedDB records without provider fields decode as Ollama
  sessions.
- No scenario, history, player action, or model response text is written to
  operational logs.

## Testing

Server tests cover:

- default and boundary port validation;
- `llama.cpp` model discovery against a local HTTP test server;
- extraction of the first model ID from `/v1/models`;
- provider selection and OpenAI-compatible request mapping;
- streamed token, done, and unavailable-service handling;
- backward-compatible decoding of Ollama turn requests.

Browser tests cover:

- session serialization/restoration of provider and port;
- mapping a `llama.cpp` session into a turn request;
- default port `8081`;
- connection-control state transitions: editable port, successful check,
  disabled port, and second-click start;
- failed checks leaving the port editable.

The full verification command remains:

```bash
./gradlew check
```

## Out of scope

- Configuring a non-local host.
- Selecting among multiple models returned by `llama.cpp`.
- Streaming directly from the browser to `llama.cpp`.
- Server-side persistence of scenarios or sessions.
- Changing the existing prompt order or claiming cache metrics that the
  active backend does not provide.
