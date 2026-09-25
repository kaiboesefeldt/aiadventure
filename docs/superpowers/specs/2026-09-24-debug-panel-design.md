# Session Debug Panel Design

## Goal

Replace the token metrics and the per-message `Info` and `World State` controls in the play view with a read-only diagnostics panel. The panel opens as an overlay from a button at the right edge, can be closed with an X button, and presents information in a readable form for a human inspecting a session.

## Scope and contents

The panel has a global section and a chronological per-turn log.

Global information:

- Current session flag values.
- The game definition's frontmatter as an expandable YAML tree.
- The game definition's system prompt as an expandable section.

Each turn's log records the information needed to understand how that turn was processed: its world-state snapshot, flags and signals at turn time, selected runtime fragments and their match information, context-compression state and operations, background LLM call results, signal emissions and resulting trigger actions, and available operational metrics. Metrics include token counts and rates, context sizes, cached token counts, durations, and call/turn status. The view shows the newest entry at the bottom and auto-scrolls on new entries only while the reader is already at the bottom.

Player input and game-master output text do not appear in debug entries. Debug entries link to their corresponding transcript locations, and transcript messages link back to the corresponding debug entry. Runtime fragments and background LLM results are diagnostic inputs/results and remain visible in the panel.

## Data flow and persistence

The server remains stateless. It emits structured diagnostic events through the existing turn stream as relevant stages complete. The browser associates events with the session and turn, renders them as labeled, human-readable entries, and persists them in IndexedDB so they survive reloads and resumed sessions. Events should cover successful, failed, and cancelled processing where available; a partial turn must not lose events already received.

Debug events live separately from the canonical game transcript/session state. Clearing the panel's log deletes only debug events for the current session. Deleting a session deletes its debug events in the same IndexedDB lifecycle operation; deleting a scenario continues to delete its sessions and their debug events. The diagnostics view is read-only apart from opening/closing the panel and clearing its log.

## Navigation and interaction

The right-edge overlay has an explicit close button. The opening control is available from the play view. Global sections and expandable context details use clear labels and readable formatting rather than raw JSON where a structured presentation is practical.

Selecting a debug-to-transcript link scrolls the main transcript to the related player or game-master message without copying its text into the debug panel. Selecting a transcript-to-debug link opens the panel if needed and navigates to the corresponding turn entry. New log events scroll into view only if the log was already at its bottom before the event arrived.

## Boundaries and invariants

- Keep scenario/session ownership in browser IndexedDB; the server does not gain persistent session state.
- Do not place player input or game-master output text in debug entries.
- Do not broaden operational server logging to include prompts, history, actions, or LLM responses. Structured client diagnostics are delivered to the explicitly requested local UI; the existing loopback-only `TRACE` logging exception remains unchanged.
- Preserve the prompt prefix, retained-slot, and cache contracts while adding diagnostics. Cache metrics describe reported llama.cpp values; only `tokens_cached` and `tokens_evaluated` establish reported cache reuse.

## Verification focus

Verify event capture and ordering across normal, failed, and cancelled turns; persistence and clearing by session; cleanup when sessions/scenarios are deleted; navigation in both directions; bottom-aware auto-scrolling; and readable rendering of global data, turn inputs, context compression, background results, signal/trigger effects, and metrics. Run the project's final `./gradlew check` without excluding dependency-install tasks and inspect `git diff --check` separately.
