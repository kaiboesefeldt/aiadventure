# AI Adventure: first vertical slice design

## Goal

Build a browser-based, AI-guided adventure game that can run on modest local
hardware. A player imports a Markdown scenario, chooses a locally available
Ollama model, and plays through a chat interface. The first slice is a flexible
game master: it improvises from the scenario and conversation, without a rules
engine or separately modelled world state.

The game works entirely locally. The browser keeps scenarios and game sessions;
the server is stateless and only calls local Ollama.

## Scope

Included:

- Import a Markdown scenario file.
- Persist scenarios and their Markdown source in the browser.
- Create, resume, and persist multiple game sessions for a scenario.
- Discover and choose a local Ollama model.
- Send player actions to an AI game master and display its reply.
- Preserve a stable prompt prefix so Ollama prompt/KV caching is effective.
- Show compact request timing and cache-relevant metrics when Ollama provides
  them.

Not included:

- Structured places, characters, inventory, or rules engine.
- Accounts, cloud sync, multiplayer, or server-side persistence.
- Scenario authoring or editing in the app.

## Architecture

The Gradle repository has two modules.

- `server`: Kotlin/JVM, Ktor, Koin, and LangChain4j. It exposes an HTTP API,
  discovers local Ollama models, validates game-master requests, and invokes
  Ollama.
- `web`: Kotlin/JS, React, Ktor client, and Koin. It renders the UI, owns all
  browser persistence, and calls the server API.

The server never stores scenarios or conversations. The client sends the
selected model, scenario Markdown, current conversation, and the player's new
action for each turn. The server returns the game master's message and any safe
execution metrics available from Ollama. The client appends both messages to the
session and writes the update to IndexedDB.

## Local data model

IndexedDB is the persistence layer. It has two stores.

`Scenario`

- `id`: local stable identifier
- `name`: imported file name or displayed title
- `markdown`: original, unmodified file content
- `importedAt`: import timestamp

`GameSession`

- `id`: local stable identifier
- `scenarioId`: referenced scenario
- `title`: user-visible session name
- `modelId`: selected Ollama model
- `messages`: ordered player and game-master messages
- `createdAt` and `updatedAt`

A scenario can have many sessions. Deleting or replacing a scenario must not
silently discard a session; the UI must make that consequence explicit when the
operation is added. The first slice only requires importing and playing.

## Prompt and cache invariant

Efficient prompt reuse is a first-class constraint. For all turns in one game
session, the server must construct the request deterministically in this exact
logical order:

1. Fixed game-master instruction.
2. The session's original scenario Markdown.
3. Prior conversation in chronological order.
4. The new player action.

The first two entries are immutable after a session begins. No timestamps,
random values, dynamic status text, or rewritten scenario text may be inserted
before or inside this prefix. Conversation only grows at the end. This enables
Ollama to reuse its prompt/KV cache between turns.

Each session uses the model selected at creation. Choosing another model starts
a new session. The Ollama connection is configured with a centrally configured
`keep_alive` duration, so a recently used model remains loaded when resources
allow it. The API response exposes duration and prompt-evaluation metrics when
Ollama supplies them; the UI shows a compact performance indicator for each
turn.

## User flow

1. The library view lists locally saved scenarios and offers a Markdown import.
2. On import, the client reads the file and stores its full text, name, and
   timestamp. Empty or unreadable files show a clear error. Re-importing the
   same file must not create accidental duplicates.
3. Selecting a scenario shows existing sessions and an action to start a new
   one. The player selects one of the models returned by the server.
4. The play view shows the scenario and model identity, ordered chat history,
   a player input field, and concise performance information. It disables
   duplicate sends while a request is pending.
5. Reloading the browser restores the library and sessions from IndexedDB;
   selecting a saved session resumes it exactly where it stopped.

## API and validation

The server provides endpoints to list available Ollama models and to request a
single game-master turn. It validates a non-empty model identifier, scenario,
and player action, and enforces conservative size limits before contacting
Ollama. It translates an unavailable Ollama service, an unavailable model, and
invalid input into distinct client-safe errors.

The application treats all LLM output as untrusted. Version 1 renders it as
plain chat content; it does not execute tools, HTML, or instructions extracted
from the model response. Standard logs record operational errors and identifiers
but do not include complete scenario or conversation text by default.

## Testing and verification

- Server tests cover deterministic prompt construction, request validation,
  model discovery translation, and Ollama error mapping.
- Web tests cover Markdown import, IndexedDB persistence, duplicate handling,
  and restored sessions.
- A manual smoke test imports a scenario, starts a game, reloads the browser,
  and resumes it.
- A two-turn manual test with the same session verifies that Ollama reports
  cache-relevant prompt metrics and that the first prompt prefix is unchanged.

## Development guidance

The repository root will contain a concise English `AGENTS.md` that records
module ownership, start and test commands, the prompt-cache invariant, the
untrusted-LLM boundary, and agent working rules. Module-level `AGENTS.md` files
are added only when a module needs instructions that cannot be stated clearly at
the root without duplication.
