package de.aidadventure.server.model

import de.aidadventure.server.RequestValidator
import de.aidadventure.server.worldstate.WorldState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApiModelsTest {
    @Test
    fun `turn JSON carries a live session without browser connection settings`() {
        val request = Json.decodeFromString<TurnRequest>("""
            {"sessionId":"live-1","scenarioMarkdown":"# S","history":[],"playerAction":"look"}
        """)

        assertEquals("live-1", request.sessionId)
        assertEquals("", request.modelId)
        assertEquals(8081, request.llamaCppPort)
    }

    @Test
    fun `prefill request carries an increasing revision`() {
        assertEquals(7L, PrefillRequest("live-1", 7, scenarioMarkdown = "# Scenario", history = emptyList(), playerAction = "look").revision)
    }

    @Test
    fun `turn JSON carries runtime fragments`() {
        val request = Json.decodeFromString<TurnRequest>("""
            {"sessionId":"live-1","scenarioMarkdown":"# S","history":[],"playerAction":"look","runtimeFragments":[{"matchText":"lighthouse","fragment":"Mira remembers it"}]}
        """.trimIndent())

        assertEquals(listOf(RuntimeFragmentDefinition("lighthouse", "Mira remembers it")), request.runtimeFragments)
    }

    @Test
    fun `request validation rejects duplicate runtime fragment matches`() {
        val error = RequestValidator.validate(
            TurnRequest(
                sessionId = "live-1",
                modelId = "configured-llama-cpp",
                scenarioMarkdown = "# S",
                history = emptyList(),
                playerAction = "look",
                runtimeFragments = listOf(
                    RuntimeFragmentDefinition("lighthouse", "Mira remembers it"),
                    RuntimeFragmentDefinition("lighthouse", "A different memory"),
                ),
            ),
        )

        assertNotNull(error)
    }

    @Test
    fun `request validation permits runtime fragment history role`() {
        val error = RequestValidator.validate(
            TurnRequest(
                sessionId = "live-1",
                modelId = "configured-llama-cpp",
                scenarioMarkdown = "# S",
                history = listOf(ChatEntry("runtime-fragment", "keep quiet")),
                playerAction = "look",
            ),
        )

        assertEquals(null, error)
    }

    @Test
    fun `request validation rejects more than 200 runtime fragment definitions`() {
        val error = RequestValidator.validate(
            TurnRequest(
                sessionId = "live-1",
                modelId = "configured-llama-cpp",
                scenarioMarkdown = "# S",
                history = emptyList(),
                playerAction = "look",
                runtimeFragments = (1..201).map { RuntimeFragmentDefinition("match-$it", "fragment") },
            ),
        )

        assertNotNull(error)
    }

    @Test
    fun `request validation rejects oversized runtime fragment text`() {
        val oversizedFragmentError = RequestValidator.validate(
            TurnRequest(
                sessionId = "live-1",
                modelId = "configured-llama-cpp",
                scenarioMarkdown = "# S",
                history = emptyList(),
                playerAction = "look",
                runtimeFragments = listOf(RuntimeFragmentDefinition("match", "x".repeat(20_001))),
            ),
        )

        val oversizedMatchTextError = RequestValidator.validate(
            TurnRequest(
                sessionId = "live-1",
                modelId = "configured-llama-cpp",
                scenarioMarkdown = "# S",
                history = emptyList(),
                playerAction = "look",
                runtimeFragments = listOf(RuntimeFragmentDefinition("x".repeat(20_001), "fragment")),
            ),
        )

        assertNotNull(oversizedFragmentError)
        assertNotNull(oversizedMatchTextError)
    }

    @Test
    fun `request validation rejects unresolved situations longer than 160 characters`() {
        val oversizedState = WorldState(unresolvedSituation = "x".repeat(161))

        assertNotNull(
            RequestValidator.validate(
                TurnRequest("live-1", "configured-llama-cpp", 8081, "# S", emptyList(), "look", worldState = oversizedState),
            ),
        )
        assertNotNull(
            RequestValidator.validate(
                PrefillRequest("live-1", 0, "configured-llama-cpp", 8081, "# S", emptyList(), "look", worldState = oversizedState),
            ),
        )
        assertNotNull(
            RequestValidator.validate(
                WarmupRequest("live-1", "configured-llama-cpp", 8081, "# S", emptyList(), worldState = oversizedState),
            ),
        )
    }

    @Test
    fun `request validation accepts an unresolved situation of 160 characters`() {
        val boundaryState = WorldState(unresolvedSituation = "x".repeat(160))

        assertNull(
            RequestValidator.validate(
                TurnRequest("live-1", "configured-llama-cpp", 8081, "# S", emptyList(), "look", worldState = boundaryState),
            ),
        )
    }
}
