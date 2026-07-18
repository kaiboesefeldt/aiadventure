# Runtime Fragment Tooltip Design

## Goal

Let a player inspect the hidden runtime fragment that influenced a completed
game-master message without exposing fragments as normal transcript text.

## Behavior

A small clickable info button appears only on a completed game-master message
whose preceding persisted sequence is runtime-fragment, then player, then
game-master.

Clicking the button opens a compact, readable tooltip adjacent to that message
with the fragment text. Clicking the button again or outside the tooltip closes
it. Messages without that sequence, including the first game-master turn, have
no button. While a response is streaming, no button is shown for its partial
game-master message.

## Persistence and safety

The association is derived from existing chronological GameMessage entries, so
it survives reload and resume without a model or IndexedDB migration. The
hidden runtime-fragment entry remains excluded from normal transcript rendering.
The tooltip is the sole intentional player-facing presentation of its content.

## Verification

Tests cover association detection, absence for unrelated or partial messages,
toggle and outside-close behavior, and a resumed persisted transcript. Browser
tests verify the button and tooltip rendering without making hidden entries
regular transcript articles.

