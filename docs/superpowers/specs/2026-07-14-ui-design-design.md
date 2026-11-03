# Quiet Game Journal UI Design

## Goal

Apply the persistent UI/UX guide in `docs/ui-ux-guide.md` to the Kotlin/JS
React browser client and let players submit an action with Enter while retaining
Shift+Enter for a newline.

## Architecture

The web module gains one local stylesheet containing the guide's design tokens,
layout primitives, component states, and responsive rules. `Main.kt` adds
semantic class names, descriptive labels, and small presentational wrappers to
the existing library, detail, and play views without changing persistence,
networking, or turn-submission ownership.

A small, pure keyboard-decision helper separates the Enter/Shift+Enter/pending
rules from the textarea event handler. The handler prevents the browser default
only when the helper permits submission, then calls the same submission path as
the Send button. Generated game-master text continues to render as text nodes.

## Interface Changes

- The app shell, headings, cards, buttons, lists, messages, errors, metrics,
  and textarea receive stable CSS class names.
- Scenario and saved-session choices become full-width action rows instead of
  unstyled list buttons.
- The transcript distinguishes player and game-master messages with text
  labels and guide-defined surfaces.
- The textarea carries an accessible label and a short Enter/Shift+Enter hint.
- `shouldSubmitOnEnter(key, shiftKey, isComposing, pending, action)` returns
  `true` only for a non-empty, non-composing Enter press without Shift while no
  turn is pending.

## Error Handling and Accessibility

Existing user-safe error messages remain adjacent to their affected views.
The visual error treatment supplements rather than replaces the text. Native
buttons and inputs retain their keyboard behavior, and focus styles are supplied
by the stylesheet. Disabled sending blocks both click and Enter submission.

## Verification

Browser tests cover the keyboard decision helper for ordinary Enter,
Shift+Enter, pending submission, IME composition, and blank actions. Existing
turn and streaming tests remain unchanged. `./gradlew check` verifies both
modules; a manual smoke test starts only `:server:run`, opens the server-served
UI, and checks that Enter sends an action while Shift+Enter adds a newline.

## Scope Boundaries

The change does not modify the server API, SSE framing, prompt construction,
Ollama configuration, persistence schema, or how untrusted LLM text is
rendered.
