# Testing the Simple Signal Test Game

Import `signal-test-game.md` again and start **Signal Test: The Nebelhafen
Lighthouse** as a new game. The separate `test-game.md` remains the complex
example for conditional runtime fragments and signal references. Open the debug
panel; all three flags are initially `false`.

The actions below are suggestions. What matters is the event actually narrated
in the completed exchange, not the intention in the action field or which
guidance the game master retrieved. A locked door or failed entry must not set
the visit flag. A search without a concrete discovery must not set the clue
flag. Likewise, only the harbor master's narrated reaction counts, not merely
an accusation or apology.

| Action / narrated result | Event check | Expected flag state |
| --- | --- | --- |
| “I enter the lighthouse.” / actually enters | `betritt-leuchtturm` → `Y` | `war-im-leuchtturm: true` |
| “I examine the tracks.” / concrete clue about the harbor case | `entdeckt-hinweis` → `Y` | `hat-hinweis: true` |
| “Your story is inconsistent!” / harbor master becomes suspicious | `verunsichert-hafenmeisterin` → `Y` | `hafenmeisterin-misstrauisch: true` |
| “I’m sorry; I want to help.” / harbor master calms down | `beruhigt-hafenmeisterin` → `Y` | `hafenmeisterin-misstrauisch: false` |

In the debug panel, inspect each event check's description, `Y` or `N` result,
and duration, token and cache metrics when provided. Flag changes must match
the result. The top-level `tokens_cached` and `tokens_evaluated` fields do not
by themselves prove prompt-cache reuse; their meaning depends on the llama.cpp
version. When available, inspect `timings.cache_n` and `timings.prompt_n` as
described in the README. Duration alone does not establish a cache hit. Event
checks run after focus/rerank selection and the completed exchange. This simple
example contains no signal references in fragments; its signals are checked
independently using `event-check`. A selected fragment with an explicit `signal`
reference triggers that signal under its conditions.

Signal conditions use the flags from the start of the turn to decide whether a
check is eligible. The two progress events are checked only while their
respective flags are `false`. After a hit, they remain `true`, including after
leaving the lighthouse and reloading or resuming the same session. Re-entering
or finding the clue again does not make another check eligible or change those
flags again.

Suspicion can change repeatedly: when it is `false`, only
`verunsichert-hafenmeisterin` is eligible; when it is `true`, only
`beruhigt-hafenmeisterin` is eligible. Repeat an accusation and reconciliation
that produce the narrated reaction. A hit does not make the opposite event
eligible for a follow-up check during that same turn. This flag must also retain
its last value when resuming.

For negative checks, start another new game. Go only to the tower and look at
it from outside: the visit flag must remain `false`, and the eligible entry
check should return `N`. Have a neutral conversation with the harbor master:
the suspicion flag must remain `false`. Also check a mere search intention or a
narrated unsuccessful attempt without a discovery. Clues may be found outside
the lighthouse; their check does not depend on visiting it. Ineligible checks
are skipped; they do not produce an `N` result.

The local model performs the classification. This guide checks its observed
answers and state changes; it cannot guarantee a particular narrative. The
automated parser test loads the actual example files and checks their structure,
not the quality or event accuracy of a model response.
