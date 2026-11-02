package de.aidadventure.server.runtimefragments

import de.aidadventure.server.model.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SignalChecksTest {
    @Test fun `selected fragment assigns flags without signals and rejects ineligible fragments`() = runBlocking {
        val fragment = kotlinx.serialization.json.Json.decodeFromString<RuntimeFragmentDefinition>(
            """{"matchText":"tower","fragment":"","conditions":[{"flag":"visited","value":false}],"setFlags":[{"flag":"visited","value":true}]}""")
        val result = runSignalChecks(request(), selectedFragment = fragment) { error("No classifier") }
        assertEquals(true, result.flags["visited"])
        assertEquals(emptyList(), result.emittedSignals)
        assertTrue(result.diagnostics.any { it.kind == "flag_set" && it.fields["flag"] == "visited" })
        val blocked = runSignalChecks(request().copy(flags = mapOf("visited" to true)), selectedFragment = fragment) { error("No classifier") }
        assertTrue(blocked.diagnostics.none { it.kind == "flag_set" })
        val unselected = runSignalChecks(request().copy(runtimeFragments = listOf(fragment))) { error("No classifier") }
        assertEquals(false, unselected.flags["visited"])
    }

    @Test fun `direct fragment assignments do not unlock rules signals or model checks in the same turn`() = runBlocking {
        val fragments = kotlinx.serialization.json.Json.decodeFromString<List<RuntimeFragmentDefinition>>(
            """[{"matchText":"","fragment":"","direct":true,"conditions":[{"flag":"visited","value":false}],"setFlags":[{"flag":"visited","value":true}]},
                {"matchText":"","fragment":"Locked","direct":true,"conditions":[{"flag":"visited","value":true}],"emittedSignals":["reward"],"setFlags":[{"flag":"reward","value":true}]}]""")
        val result = runSignalChecks(request().copy(runtimeFragments = fragments,
            signalChecks = listOf(SignalCheckDefinition("reward", "Reward", listOf(FlagCondition("visited", true)))))) { error("No cascade") }
        assertEquals(mapOf("visited" to true, "reward" to false), result.flags)
        assertEquals(emptyList(), result.emittedSignals)
        assertEquals(emptyList(), result.emittedTexts)
    }

    @Test fun `signals without event check never synthesize name or fragment predicates`() = runBlocking {
        val request = request().copy(runtimeFragments = listOf(
            RuntimeFragmentDefinition("tower", "Guidance", emittedSignals = listOf("enter"))))
        var checks = 0
        val result = runSignalChecks(request) { checks++; "Y" }
        assertEquals(0, checks)
        assertEquals(emptyList(), result.emittedSignals)
        assertEquals(mapOf("visited" to false, "reward" to false), result.flags)
    }

    @Test fun `selected fragment checks inherited exclusions and signal location before firing once`() = runBlocking {
        val fragment = RuntimeFragmentDefinition("tower\nroom", "Guidance", emittedSignals = listOf("enter", "enter"),
            excludedConditions = listOf(listOf(FlagCondition("reward", true))))
        for (predicate in listOf(null, "", " ", "The player entered")) for (blocked in listOf(false, true)) for (atSignalLocation in listOf(false, true)) {
            val request = request().copy(flags = mapOf("visited" to false, "reward" to blocked),
                worldState = de.aidadventure.server.worldstate.WorldState(location = if (atSignalLocation) "Kai" else "Tower"),
                signals = mapOf("enter" to listOf(FlagAssignment("visited", true))),
                signalChecks = listOf(SignalCheckDefinition("enter", predicate,
                    listOf(FlagCondition(location = "Kai")), emittedTexts = listOf("Signal guidance"))))
            val outcome = runSignalChecks(request, selectedFragment = fragment) { "N" }
            val triggered = !blocked && atSignalLocation
            assertEquals(if (triggered) listOf("enter") else emptyList(), outcome.emittedSignals)
            assertEquals(if (triggered) listOf("Signal guidance") else emptyList(), outcome.emittedTexts)
            assertEquals(triggered, outcome.flags["visited"])
            if (triggered) assertTrue(outcome.diagnostics.none { it.kind == "signal_check" })
        }
    }

    @Test fun `location entry runs its actions in order and fires referenced signals without model checks`() = runBlocking {
        val request = request().copy(
            flags = mapOf("visited" to false, "reward" to false),
            signals = linkedMapOf(
                "arrival" to listOf(FlagAssignment("visited", true)),
                "reward" to listOf(FlagAssignment("reward", true)),
            ),
            signalChecks = listOf(
                SignalCheckDefinition("arrival", "Arrive", emittedTexts = listOf("Signal text")),
                SignalCheckDefinition("reward", "Get reward"),
            ),
            locationTriggers = mapOf("Kai" to listOf(
                LocationTriggerAction(setFlags = listOf(FlagAssignment("reward", false))),
                LocationTriggerAction(emitText = "Welcome to Kai."),
                LocationTriggerAction(signals = listOf("arrival", "reward")),
                LocationTriggerAction(setFlags = listOf(FlagAssignment("visited", false))),
            )),
            enteredLocations = listOf("Kai"),
        )
        var checks = 0
        val result = runSignalChecks(request) { checks++; "N" }
        assertEquals(0, checks)
        assertEquals(listOf("arrival", "reward"), result.emittedSignals)
        assertEquals(mapOf("visited" to false, "reward" to true), result.flags)
        assertEquals(listOf("Welcome to Kai.", "Signal text"), result.emittedTexts)
        val effects = result.diagnostics.filter { it.kind == "flag_set" || it.kind == "text_emitted" }
        assertEquals(listOf("flag_set", "text_emitted", "flag_set", "text_emitted", "flag_set", "flag_set"), effects.map { it.kind })
        assertEquals(listOf("reward", null, "visited", null, "reward", "visited"), effects.map { it.fields["flag"] })
    }

    @Test fun `location actions do not repeat unless a location entry is supplied`() = runBlocking {
        val request = request().copy(
            signals = mapOf("arrival" to listOf(FlagAssignment("visited", true))),
            signalChecks = listOf(SignalCheckDefinition("arrival", "Arrive", listOf(FlagCondition(location = "Kai")))),
            locationTriggers = mapOf("Kai" to listOf(LocationTriggerAction(signals = listOf("arrival")))),
        )
        var checks = 0
        val noEntry = runSignalChecks(request) { checks++; "Y" }
        assertTrue(noEntry.emittedSignals.isEmpty())
        assertEquals(mapOf("visited" to false, "reward" to false), noEntry.flags)

        val startEntry = runSignalChecks(request.copy(
            enteredLocations = listOf("Kai"),
            worldState = de.aidadventure.server.worldstate.WorldState(location = "Kai"),
        )) { checks++; "N" }
        assertEquals(listOf("arrival"), startEntry.emittedSignals)
        assertEquals(mapOf("visited" to true, "reward" to false), startEntry.flags)
        assertEquals(0, checks)
    }

    @Test fun `location conditions gate event checks direct rules inherited references and else groups`() = runBlocking {
        val condition = kotlinx.serialization.json.Json.decodeFromString<FlagCondition>("""{"location":"Leuchtturm"}""")
        val predicates = listOf(condition, FlagCondition("visited", false))
        val fragments = listOf(
            RuntimeFragmentDefinition("", "At tower", predicates, listOf("enter"), direct = true),
            RuntimeFragmentDefinition("", "Elsewhere", direct = true, excludedConditions = listOf(predicates)),
            RuntimeFragmentDefinition("tower", "Retrieved", predicates, listOf("enter")))
        val base = request().copy(signals = mapOf("enter" to listOf(FlagAssignment("visited", true))), runtimeFragments = fragments,
            signalChecks = listOf(SignalCheckDefinition("enter", "Arrived", predicates)))
        val here = base.copy(worldState = de.aidadventure.server.worldstate.WorldState(location = "Leuchtturm"))
        assertEquals(listOf("tower"), eligibleRuntimeFragments(fragments, here.flags, here.worldState.location).map { it.matchText })
        assertEquals(listOf("enter"), eligibleSignalChecks(here).map { it.name })
        val result = runSignalChecks(here) { error("Direct rule handles signal") }
        assertEquals(listOf("At tower"), result.emittedTexts)
        assertEquals(listOf("enter"), result.emittedSignals)
        for (location in listOf("Kai", "leuchtturm", "")) {
            val elsewhere = here.copy(worldState = here.worldState.copy(location = location))
            assertTrue(eligibleRuntimeFragments(fragments, elsewhere.flags, elsewhere.worldState.location).isEmpty())
            assertTrue(eligibleSignalChecks(elsewhere).isEmpty())
            assertTrue(eligibleSignalChecks(elsewhere.copy(signalChecks = emptyList())).isEmpty())
            val excluded = runSignalChecks(elsewhere) { error("Location must block checks") }
            assertEquals(listOf("Elsewhere"), excluded.emittedTexts)
            assertTrue(excluded.emittedSignals.isEmpty())
        }
        val signalExcluded = here.copy(runtimeFragments = emptyList(), signalChecks = listOf(
            SignalCheckDefinition("enter", "Else event", excludedConditions = listOf(predicates))))
        assertTrue(eligibleSignalChecks(signalExcluded).isEmpty())
        assertTrue(eligibleSignalChecks(here.copy(flags = here.flags + ("visited" to true))).isEmpty())
    }

    @Test fun `inactive direct only signal never becomes a legacy event predicate without check metadata`() = runBlocking {
        val request = request().copy(signals = mapOf("enter" to listOf(FlagAssignment("visited", true))),
            runtimeFragments = listOf(RuntimeFragmentDefinition("", "", listOf(FlagCondition("visited", true)), listOf("enter"), direct = true)))
        var checks = 0
        val outcome = runSignalChecks(request) { checks++; "Y" }
        assertEquals(emptyList(), outcome.emittedSignals)
        assertEquals(mapOf("visited" to false, "reward" to false), outcome.flags)
        assertEquals(0, checks)
    }

    @Test fun `direct references deduplicate effects and suppress independent event check`() = runBlocking {
        val checks = listOf(
            SignalCheckDefinition("enter", "An independent event", listOf(FlagCondition("reward", false)), listOf("Signal")))
        val direct = RuntimeFragmentDefinition("", "", emittedSignals = listOf("enter"), direct = true)
        val result = runSignalChecks(request().copy(signals = mapOf("enter" to listOf(FlagAssignment("visited", true))),
            signalChecks = checks, runtimeFragments = listOf(direct, direct))) { error("Direct signal must deduplicate classifier") }
        assertEquals(listOf("enter"), result.emittedSignals)
        assertEquals(listOf("Signal"), result.emittedTexts)
        assertEquals(listOf("direct"), result.diagnostics.filter { it.kind == "signal_detected" }.map { it.fields["detection"] })
    }

    @Test fun `direct rules select all matches and else exclusions from original flags without classification`() = runBlocking {
        val request = request().copy(signalChecks = listOf(
            SignalCheckDefinition("enter", null, emittedTexts = listOf("Signal text")),
            SignalCheckDefinition("reward", null, listOf(FlagCondition("visited", true)))),
            runtimeFragments = listOf(
                RuntimeFragmentDefinition("", "Direct one", listOf(FlagCondition("visited", false)), listOf("enter"), direct = true),
                RuntimeFragmentDefinition("", "Direct two", listOf(FlagCondition("reward", false)), listOf("enter", "reward"), direct = true),
                RuntimeFragmentDefinition("", "Else", direct = true, excludedConditions = listOf(listOf(FlagCondition("visited", false)), listOf(FlagCondition("reward", false))))))
        val result = runSignalChecks(request) { error("Direct rules must not call the event classifier") }
        assertEquals(listOf("enter"), result.emittedSignals)
        assertEquals(mapOf("visited" to true, "reward" to false), result.flags)
        assertEquals(listOf("Direct one", "Direct two", "Signal text"), result.emittedTexts)
        assertTrue(eligibleRuntimeFragments(request.runtimeFragments, request.flags).isEmpty())
    }

    @Test fun `excluded predicates gate named retrieval and signal event checks`() = runBlocking {
        val exclusion = listOf(listOf(FlagCondition("visited", false)))
        val fragment = RuntimeFragmentDefinition("else", "Fallback", excludedConditions = exclusion)
        assertTrue(eligibleRuntimeFragments(listOf(fragment), mapOf("visited" to false)).isEmpty())
        assertEquals(listOf(fragment), eligibleRuntimeFragments(listOf(fragment), mapOf("visited" to true)))
        val check = SignalCheckDefinition("enter", "Fallback", excludedConditions = exclusion)
        val result = runSignalChecks(request().copy(signalChecks = listOf(check), signals = mapOf("enter" to listOf(FlagAssignment("visited", true))))) { "Y" }
        assertEquals(emptyList(), result.emittedSignals)
    }

    @Test fun `texts follow signal declaration and trigger order without flag cascades`() = runBlocking {
        val request = request().copy(signalChecks = listOf(
            SignalCheckDefinition("reward", "Reward", emittedTexts = listOf("Third")),
            SignalCheckDefinition("enter", "Entry", emittedTexts = listOf("First", "Second"))))
        val result = runSignalChecks(request) { "Y" }
        assertEquals(listOf("First", "Second", "Third"), result.emittedTexts)
    }

    @Test fun `text signals still check when assignments are unchanged`() = runBlocking {
        val check = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SignalCheckDefinition>(
            """{"name":"enter","eventCheck":"Entered","emittedTexts":["Remember"]}""")
        val checked = mutableListOf<String>()
        runSignalChecks(request().copy(signals = mapOf("enter" to listOf(FlagAssignment("visited", false))), signalChecks = listOf(check))) {
            checked += it.name; "Y"
        }
        assertEquals(listOf("enter"), checked)
    }

    @Test fun `effect diagnostics retain sequential repeated assignments and every text with origin`() = runBlocking {
        val result = runSignalChecks(request().copy(signals = linkedMapOf(
            "enter" to listOf(FlagAssignment("visited", false), FlagAssignment("visited", true), FlagAssignment("visited", true))),
            signalChecks = listOf(SignalCheckDefinition("enter", "Entry", emittedTexts = listOf("Same", "Same"))),
            runtimeFragments = listOf(RuntimeFragmentDefinition("", "Direct", direct = true)))) { "Y" }
        val effects = result.diagnostics.filter { it.kind in setOf("signal_detected", "flag_set", "text_emitted") }
        assertEquals(listOf("text_emitted", "signal_detected", "flag_set", "flag_set", "flag_set", "text_emitted", "text_emitted"), effects.map { it.kind })
        assertEquals(listOf("false", "false", "true"), effects.filter { it.kind == "flag_set" }.map { it.fields["previousValue"] })
        assertEquals(listOf("false", "true", "true"), effects.filter { it.kind == "flag_set" }.map { it.fields["value"] })
        assertEquals(listOf("flag_rule", "signal", "signal"), effects.filter { it.kind == "text_emitted" }.map { it.fields["sourceType"] })
        assertEquals("model", effects.single { it.kind == "signal_detected" }.fields["detection"])
    }

    private fun request() = WarmupRequest("session", scenarioMarkdown = "# Game", history = listOf(
        ChatEntry("player", "Enter"), ChatEntry("game-master", "You entered the lighthouse.")),
        flags = mapOf("visited" to false, "reward" to false),
        signals = linkedMapOf("enter" to listOf(FlagAssignment("visited", true)),
            "reward" to listOf(FlagAssignment("reward", true))))

    @Test fun `explicit predicates recognize events independently and eligibility uses turn start flags`() = runBlocking {
        val request = request().copy(signalChecks = listOf(
            SignalCheckDefinition("enter", "The player entered the lighthouse.", listOf(FlagCondition("visited", false))),
            SignalCheckDefinition("reward", "The player received a reward.", listOf(FlagCondition("visited", true)))),
            runtimeFragments = listOf(RuntimeFragmentDefinition("irrelevant", "Guidance", listOf(FlagCondition("visited", true)), listOf("enter"))))
        val checked = mutableListOf<String>()
        val result = runSignalChecks(request) { check -> checked += requireNotNull(check.eventCheck); "Y" }
        assertEquals(listOf("The player entered the lighthouse."), checked)
        assertEquals(listOf("enter"), result.emittedSignals)
        assertEquals(mapOf("visited" to true, "reward" to false), result.flags)
    }

    @Test fun `checks are sequential and only exact Y commits events`() = runBlocking {
        val request = request().copy(signalChecks = listOf(SignalCheckDefinition("enter", "Entry"), SignalCheckDefinition("reward", "Reward")))
        for (answer in listOf("N", " Y", "Y\n", "yes", "")) {
            val checked = mutableListOf<String>()
            val result = runSignalChecks(request) { check -> checked += check.name; if (check.name == "enter") answer else "Y" }
            assertEquals(listOf("enter", "reward"), checked)
            assertEquals(mapOf("visited" to false, "reward" to true), result.flags)
        }
    }

    @Test fun `only nonblank event checks run and equivalent predicates collapse per signal`() = runBlocking {
        for (predicate in listOf(null, "", " \n ")) {
            val request = request().copy(signalChecks = listOf(SignalCheckDefinition("enter", predicate)))
            val outcome = runSignalChecks(request) { error("Missing or blank predicate must never call model") }
            assertTrue(outcome.emittedSignals.isEmpty())
            assertEquals(request.flags, outcome.flags)
        }
        val request = request().copy(signalChecks = listOf(
            SignalCheckDefinition("enter", "Entry"), SignalCheckDefinition("enter", "Entry"),
            SignalCheckDefinition("enter", "Arrival"), SignalCheckDefinition("reward", null)))
        val checked = mutableListOf<String>()
        val outcome = runSignalChecks(request) { checked += requireNotNull(it.eventCheck); "Y" }
        assertEquals(listOf("Entry\nOR\nArrival"), checked)
        assertEquals(listOf("enter"), outcome.emittedSignals)
    }

    @Test fun `a failed predicate does not commit positive flags or stop later checks`() = runBlocking {
        val result = runSignalChecks(request().copy(signalChecks = listOf(SignalCheckDefinition("enter", "Entry"), SignalCheckDefinition("reward", "Reward")))) { check -> if (check.name == "enter") error("secret") else "Y" }
        assertEquals(listOf("reward"), result.emittedSignals)
        assertEquals(mapOf("visited" to false, "reward" to true), result.flags)
        assertEquals("failed", result.diagnostics.first().stage)
        assertFalse(result.diagnostics.toString().contains("secret"))
    }

    @Test fun `signals without effective flag changes never call the checker`() = runBlocking {
        for (explicit in listOf(false, true)) {
            val signals = linkedMapOf(
                "unchanged" to listOf(FlagAssignment("visited", false), FlagAssignment("reward", false)),
                "empty" to emptyList(),
                "restored" to listOf(FlagAssignment("visited", true), FlagAssignment("visited", false)),
                "blocked" to listOf(FlagAssignment("visited", true)),
            )
            val request = request().copy(signals = signals,
                signalChecks = if (explicit) signals.keys.map { name ->
                    SignalCheckDefinition(name, name, if (name == "blocked") listOf(FlagCondition("reward", true)) else emptyList())
                } else emptyList(),
                runtimeFragments = if (explicit) emptyList() else listOf(
                    RuntimeFragmentDefinition("Unchanged", "Guidance", emittedSignals = listOf("unchanged", "empty", "restored")),
                    RuntimeFragmentDefinition("Blocked", "Guidance", listOf(FlagCondition("reward", true)), listOf("blocked"))))
            val checked = mutableListOf<String>()
            val result = runSignalChecks(request) { checked += it.name; "Y" }
            assertEquals(emptyList(), checked, "explicit=$explicit")
            assertEquals(emptyList(), result.emittedSignals)
            assertEquals(mapOf("visited" to false, "reward" to false), result.flags)
        }
    }

    @Test fun `partial changes final repeated assignments and true to false changes call the checker`() = runBlocking {
        val request = request().copy(flags = mapOf("visited" to false, "reward" to true, "extra" to false), signals = linkedMapOf(
            "partial" to listOf(FlagAssignment("visited", false), FlagAssignment("extra", true)),
            "repeated" to listOf(FlagAssignment("visited", true), FlagAssignment("visited", false), FlagAssignment("visited", true)),
            "clear" to listOf(FlagAssignment("reward", false)),
        ))
        val checked = mutableListOf<String>()
        val result = runSignalChecks(request.copy(signalChecks = listOf(
            SignalCheckDefinition("partial", "Partial"), SignalCheckDefinition("repeated", "Repeated"), SignalCheckDefinition("clear", "Clear")))) { checked += it.name; "Y" }
        assertEquals(listOf("partial", "repeated", "clear"), checked)
        assertEquals(listOf("partial", "repeated", "clear"), result.emittedSignals)
        assertEquals(mapOf("visited" to true, "reward" to false, "extra" to true), result.flags)
    }

    @Test fun `effect eligibility stays at turn start despite earlier assignments`() = runBlocking {
        val request = request().copy(signals = linkedMapOf(
            "enter" to listOf(FlagAssignment("visited", true)),
            "reset" to listOf(FlagAssignment("visited", false)),
            "enterAgain" to listOf(FlagAssignment("visited", true)),
        ))
        val checked = mutableListOf<String>()
        val result = runSignalChecks(request.copy(signalChecks = listOf(
            SignalCheckDefinition("enter", "Entry"), SignalCheckDefinition("reset", "Reset"), SignalCheckDefinition("enterAgain", "Reentry")))) { checked += it.name; "Y" }
        assertEquals(listOf("enter", "enterAgain"), checked)
        assertEquals(listOf("enter", "enterAgain"), result.emittedSignals)
        assertEquals(mapOf("visited" to true, "reward" to false), result.flags)
    }
}
