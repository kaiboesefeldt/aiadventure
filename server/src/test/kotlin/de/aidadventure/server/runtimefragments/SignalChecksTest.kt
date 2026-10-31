package de.aidadventure.server.runtimefragments

import de.aidadventure.server.model.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SignalChecksTest {
    @Test fun `inactive direct only signal never becomes a legacy event predicate without check metadata`() = runBlocking {
        val request = request().copy(signals = mapOf("enter" to listOf(FlagAssignment("visited", true))),
            runtimeFragments = listOf(RuntimeFragmentDefinition("", "", listOf(FlagCondition("visited", true)), listOf("enter"), direct = true)))
        var checks = 0
        val outcome = runSignalChecks(request) { checks++; "Y" }
        assertEquals(emptyList(), outcome.emittedSignals)
        assertEquals(mapOf("visited" to false, "reward" to false), outcome.flags)
        assertEquals(0, checks)
    }

    @Test fun `direct declaration metadata overrides unrelated named fallback conditions and deduplicates event check`() = runBlocking {
        val checks = listOf(
            SignalCheckDefinition("enter", "", listOf(FlagCondition("reward", false)), listOf("Signal"), eventCheck = false),
            SignalCheckDefinition("enter", "Named fallback", listOf(FlagCondition("visited", true)), listOf("Signal")))
        val direct = RuntimeFragmentDefinition("", "", emittedSignals = listOf("enter"), direct = true)
        val result = runSignalChecks(request().copy(signals = mapOf("enter" to listOf(FlagAssignment("visited", true))),
            signalChecks = checks, runtimeFragments = listOf(direct, direct))) { error("Direct signal must deduplicate classifier") }
        assertEquals(listOf("enter"), result.emittedSignals)
        assertEquals(listOf("Signal"), result.emittedTexts)
        assertEquals(listOf("direct"), result.diagnostics.filter { it.kind == "signal_detected" }.map { it.fields["detection"] })
    }

    @Test fun `direct rules select all matches and else exclusions from original flags without classification`() = runBlocking {
        val request = request().copy(signalChecks = listOf(
            SignalCheckDefinition("enter", "Entry", emittedTexts = listOf("Signal text"), eventCheck = false),
            SignalCheckDefinition("reward", "Reward", listOf(FlagCondition("visited", true)), eventCheck = false)),
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
            """{"name":"enter","description":"Entered","emittedTexts":["Remember"]}""")
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
        val result = runSignalChecks(request) { check -> checked += check.description; "Y" }
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

    @Test fun `legacy eligible paths collapse into one predicate per declared signal`() = runBlocking {
        val request = request().copy(runtimeFragments = listOf(
            RuntimeFragmentDefinition("entry", "A", emittedSignals = listOf("enter")),
            RuntimeFragmentDefinition("arrival", "B", emittedSignals = listOf("enter")),
            RuntimeFragmentDefinition("locked", "C", listOf(FlagCondition("visited", true)), listOf("enter"))))
        val checked = mutableListOf<SignalCheckDefinition>()
        val result = runSignalChecks(request) { checked += it; "Y" }
        assertEquals(listOf("enter", "reward"), checked.map { it.name })
        assertContains(checked.first().description, "entry")
        assertContains(checked.first().description, "arrival")
        assertFalse(checked.first().description.contains("locked"))
        assertEquals(mapOf("visited" to true, "reward" to true), result.flags)
    }

    @Test fun `a failed predicate does not commit positive flags or stop later checks`() = runBlocking {
        val result = runSignalChecks(request()) { check -> if (check.name == "enter") error("secret") else "Y" }
        assertEquals(listOf("reward"), result.emittedSignals)
        assertEquals(mapOf("visited" to false, "reward" to true), result.flags)
        assertEquals("failed", result.diagnostics.first().stage)
        assertFalse(result.diagnostics.toString().contains("secret"))
    }

    @Test fun `explicit and legacy signals without effective flag changes never call the checker`() = runBlocking {
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
        val result = runSignalChecks(request) { checked += it.name; "Y" }
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
        val result = runSignalChecks(request) { checked += it.name; "Y" }
        assertEquals(listOf("enter", "enterAgain"), checked)
        assertEquals(listOf("enter", "enterAgain"), result.emittedSignals)
        assertEquals(mapOf("visited" to true, "reward" to false), result.flags)
    }
}
