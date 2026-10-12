package de.aidadventure.server.runtimefragments

import de.aidadventure.server.model.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SignalChecksTest {
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
}
