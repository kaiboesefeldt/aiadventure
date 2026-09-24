package de.aidadventure.server.runtimefragments

import de.aidadventure.server.model.FlagAssignment
import de.aidadventure.server.model.FlagCondition
import de.aidadventure.server.model.RuntimeFragmentDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeSignalStateTest {
    @Test
    fun `conditions filter candidates using original flags`() {
        val fragments = listOf(
            RuntimeFragmentDefinition("open", "Open", listOf(FlagCondition("has-key", true))),
            RuntimeFragmentDefinition("closed", "Closed", listOf(FlagCondition("has-key", false))),
        )

        assertEquals(listOf(fragments[1]), eligibleRuntimeFragments(fragments, mapOf("has-key" to false)))
    }

    @Test
    fun `later emitted signals overwrite earlier flag assignments`() {
        val signals = mapOf(
            "enter" to listOf(FlagAssignment("visited", true)),
            "forget" to listOf(FlagAssignment("visited", false)),
        )

        assertEquals(mapOf("visited" to false), applyRuntimeSignals(mapOf("visited" to false), signals, listOf("enter", "forget")))
    }
}
