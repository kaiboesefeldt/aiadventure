# Scenario and Session Deletion Design

## Goal

Let players immediately remove browser-local content they no longer want:

- Deleting a scenario removes that scenario and every saved session that belongs to it.
- Deleting a session removes only that session.
- Neither action presents a confirmation dialog.

## User interface

The library renders each scenario as one action row. The scenario title remains
the primary control for opening its detail page. A separate, subdued button on
the right side of that row is labelled `Delete`; it is visually and structurally
separate so clicking it cannot open the scenario.

The detail page uses the same row pattern for saved sessions: the title resumes
the session and a right-aligned `Delete` button removes that one session.
Deleting a scenario is therefore visibly scoped to the scenario row, while
deleting a session is visibly scoped to its own session row.

After a successful delete, the affected list is reloaded from IndexedDB. The
deleted row disappears immediately. The library remains the current view after
scenario deletion, and the detail page remains the current view after session
deletion. No other navigation or server call is involved.

## Persistence

`ScenarioRepository` and `SessionRepository` gain explicit deletion methods.
The generic IndexedDB store supports deleting one record by ID.

Scenario deletion is implemented as one read-write IndexedDB transaction over
the `scenarios` and `sessions` stores. It deletes the scenario, finds session
records through the `scenarioId` index, and deletes every matching session
before the transaction completes. This prevents a partially deleted scenario
from leaving orphaned sessions if the operation fails.

Session deletion uses a read-write transaction on the sessions store and deletes
only the requested session ID.

## Error handling

If IndexedDB rejects a deletion, the current list remains visible and the
existing client-facing persistence error is rendered. UI controls are disabled
while their delete operation is pending, preventing duplicate clicks.

## Testing

Persistence tests prove that a scenario deletion removes its matching sessions
but preserves sessions for other scenarios, and that single-session deletion is
scoped to the requested ID. Browser component tests prove each delete button
has the stated scope and refreshes the list without a confirmation prompt.

Existing IndexedDB transaction completion handling remains the error boundary;
the change does not touch prompt construction, warmup, prefill, turn
submission, coordinator lifecycle, or llama.cpp slot handling.
