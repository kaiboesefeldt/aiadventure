# UI/UX Guide

## Purpose

AI Adventure uses a quiet, readable "game journal" style. The interface should
help a player focus on the scenario and the narrated game, rather than resemble
a dashboard or a game launcher.

## Typography

- Use the platform UI sans-serif stack for controls, navigation, labels, and
  metadata: `system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`.
- Use `Georgia, "Times New Roman", serif` only for page titles, scenario titles,
  and game-master narration.
- Body text is at least 16px with a 1.5 line height. Narration uses a 1.65 line
  height for comfortable reading.
- Keep labels and button text in sentence case; do not use all caps.

## Color

| Token | Value | Use |
| --- | --- | --- |
| Canvas | `#f6f5f0` | Page background |
| Surface | `#ffffff` | Cards and message panels |
| Ink | `#1f2933` | Primary text |
| Muted ink | `#66727f` | Supporting text and metadata |
| Rule | `#d7dce0` | Borders and dividers |
| Accent | `#2f6f6d` | Primary action, links, and focus |
| Accent hover | `#255a58` | Hovered primary action |
| Accent wash | `#e4f0ee` | Player-message surface and selected items |
| Error | `#9c2f2f` | Error text and error border |

Text must use `Ink` or a contrast-equivalent color on `Canvas` and `Surface`.
Accent color is not the only indication of state: selection, errors, and
disabled controls must also use text, borders, or opacity.

## Layout and Shape

- Center the application in a column with a 72rem maximum width and 24px outer
  padding on desktop; reduce to 16px on narrow screens.
- Use one clear page heading and one primary card per view. Supporting sections
  sit inside that card with a thin divider rather than nested decorative cards.
- Use 10px corner radii, 1px `Rule` borders, and no drop shadows.
- Use an 8px spacing rhythm. Keep controls at least 40px high.
- The active navigation control and primary action use `Accent`; secondary
  controls are quiet outlined buttons.

## Content Patterns

- Scenario and session lists use full-width rows with a title, concise metadata,
  and a visibly clickable action.
- Player messages use the `Accent wash` surface; game-master messages use the
  neutral `Surface` with serif narration. Both identify their speaker in text.
- Errors appear near the affected control with the `Error` color and plain,
  actionable copy.
- Metrics remain secondary metadata below the transcript.

## Forms and Keyboard Interaction

- Textareas have a visible focus ring in `Accent` and a clear label.
- **Enter submits a non-empty action. Shift+Enter inserts a newline.**
- Enter must not submit while a response is pending or while IME composition is
  active. The Send button follows the same disabled rule.
- Typing may trigger a silent, debounced prefill. It must not add a transcript
  entry, show an error, disable Send, or otherwise change the reading flow.
- File selection and model/session actions remain operable with keyboard focus.

## Responsive Behavior

- Preserve the single-column reading flow at every width.
- Allow long model IDs and scenario titles to wrap instead of overflowing.
- On narrow screens, buttons may fill the available width when paired actions
  would otherwise become cramped.

## Boundaries

- Do not render generated text as HTML; keep it as text nodes.
- Do not introduce external font, icon, image, or stylesheet dependencies.
- Browser IndexedDB remains the owner of scenarios and sessions.
