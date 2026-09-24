# Task 3: Debug panel UI and global information

## Scope

- Updated `web/src/jsMain/kotlin/de/aidadventure/web/Main.kt` to load current-session diagnostics, refresh them while the panel is open, and remove the inline World State, Info, and token metrics controls.
- Added `web/src/jsMain/kotlin/de/aidadventure/web/DebugPanel.kt` for the overlay, global sections, recursive YAML tree, labeled event cards, metrics, scoped clear action, keyboard close, and bottom-aware scrolling.
- Updated `web/src/jsMain/resources/app.css` for the fixed right-edge opener, responsive panel, readable card hierarchy, and focus styling.
- Updated `AdventureAppNavigationTest.kt` and `WorldStatePopoverTest.kt`, added `DebugPanelTest.kt`, and removed obsolete tooltip helper tests from `StreamedSessionTest.kt`.

The two new component files and `StreamedSessionTest.kt` extend the brief's initial file list. The pre-existing historical `.superpowers/sdd/task-3-report.md` remains unchanged.

## RED evidence

1. `./gradlew :web:browserTest --tests 'de.aidadventure.web.AdventureAppNavigationTest.play view puts diagnostics behind a closable panel without inline controls'` failed: the old inline control remained and the panel opener was absent.
2. Focused `:web:browserTest` runs for `debug panel renders global information and labeled turn diagnostics` and `clearing debug log removes only active session events` failed before implementation because the panel opener was absent.
3. `./gradlew :web:browserTest --tests 'de.aidadventure.web.AdventureAppNavigationTest.open debug panel refreshes diagnostics added during a turn'` failed with zero cards after a new event was appended while the panel was open; this drove active refresh.
4. The first scroll test run failed because the browser clamped the scroll position to zero in the test fixture without a scroll range. After defining scroll dimensions and writable scroll position, the focused test passed without changing production scrolling behavior.
5. The focused cyclic YAML alias test initially reproduced a stack overflow in the recursive frontmatter tree. Tracking ancestor objects with a `WeakSet` made that test pass.

## GREEN evidence

- `./gradlew :web:compileTestKotlinJs` passed.
- `./gradlew :web:browserTest` passed after the panel, clear action, and scrolling implementation.
- The focused live-refresh test passed after adding active refresh.
- Final serial `./gradlew :web:compileTestKotlinJs` and `./gradlew :web:browserTest` both passed. The browser suite ran 94 tests with zero failures or errors.
- `git diff --check` passed after final review.

## Review

The panel derives current flags from the displayed session and uses `ScenarioFrontmatter.parse(...).promptMarkdown` for the system-prompt body. Diagnostic fields are presented as labels and values, and player-action/model-output field names are excluded. Events follow the diagnostic repository's persisted order; `source=prior_turn` remains visible. Clearing calls `clearSession` with the active session ID. The panel is read-only apart from this action. Transcript navigation remains for Task 4.

## Review follow-up: ordering and focus

- RED: a focused browser run of `DebugPanelTest.repository order survives a reset turn counter after reload` and `AdventureAppNavigationTest.play view puts diagnostics behind a closable panel without inline controls` failed both tests. The UI sort moved a new post-reload turn before older persisted turns; closing by X left focus on the removed close control.
- GREEN: the panel now renders the repository's order without re-sorting. The close callback returns focus to the fixed opener after X and Escape. The navigation test asserts focus for both paths.
- Focused `./gradlew :web:browserTest --tests 'de.aidadventure.web.DebugPanelTest' --tests 'de.aidadventure.web.AdventureAppNavigationTest'` passed.
- Final serial `./gradlew :web:compileTestKotlinJs` and `./gradlew :web:browserTest` passed. The full browser suite ran 95 tests with zero failures or errors; `git diff --check` passed.
