# Server-configured llama.cpp connection

## Goal

Remove llama.cpp port entry and model selection from the browser. The server
uses one localhost llama.cpp instance, configured at startup, while each
scenario page only lets users create or resume browser-local sessions.

## Configuration and server contract

`main(args)` accepts `--llama-port=<port>` and defaults to `8081`. Invalid,
unknown, or duplicate arguments fail startup with a clear error. The selected
port is always addressed through `localhost` by the server.

The server owns the connection details. It discovers the single model exposed
by that llama.cpp instance and supplies its model identifier to prefill,
warmup, and turn handling. Browser requests no longer carry a port or model.
The obsolete `/api/models` endpoint is removed.

## Browser and persistence

The scenario detail page shows saved sessions, a `New session` button, and
existing deletion controls. Creating a session creates it directly without
network model discovery. Resuming a session simply opens it; it does not
revalidate a selected model or port.

New browser-local sessions contain only scenario, title, messages, and
timestamps. The IndexedDB schema migrates existing records by ignoring their
legacy port/model fields. A stable server-derived model identifier is retained
only where the existing prompt-cache coordinator requires it, never in a
browser request or UI choice.

## Cache and error behavior

The server resolves the configured model before a cache-sensitive request and
uses that same identifier consistently for prefill, warmup, and turn
coordination. Session IDs continue to be preserved exactly between these
operations. Unavailable llama.cpp or model discovery errors return the
existing sanitized gateway errors and operational logs contain only request
IDs and failure classes.

## Tests

Server tests cover default and explicit command-line ports, invalid arguments,
and routing requests that omit browser-controlled connection details. Browser
tests cover direct session creation and resume from a scenario page without
model discovery controls. Existing prompt-cache contract tests remain intact.
