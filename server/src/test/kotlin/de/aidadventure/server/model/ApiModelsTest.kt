package de.aidadventure.server.model

import de.aidadventure.server.RequestValidator
import de.aidadventure.server.worldstate.WorldState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApiModelsTest {
    @Test
    fun `independent check validation rejects unknown signals flags and blank descriptions`() {
        val request = TurnRequest("live", modelId = "configured-llama-cpp", scenarioMarkdown = "# S", history = emptyList(), playerAction = "look",
            flags = mapOf("visited" to false), signals = mapOf("enter" to listOf(FlagAssignment("visited", true))))
        for (check in listOf(SignalCheckDefinition("unknown", "Event"), SignalCheckDefinition("enter", " "),
            SignalCheckDefinition("enter", "Event", listOf(FlagCondition("unknown", true))))) {
            assertNotNull(RequestValidator.validate(request.copy(signalChecks = listOf(check))))
        }
    }

    @Test
    fun `turn accepts independent event predicates without fragment references`() {
        val request = Json.decodeFromString<TurnRequest>("""
            {"sessionId":"live-1","scenarioMarkdown":"# S","history":[],"playerAction":"enter",
             "flags":{"visited":false},"signals":{"enter":[{"flag":"visited","value":true}]},
             "signalChecks":[{"name":"enter","description":"The player entered the lighthouse.","conditions":[{"flag":"visited","value":false}]}]}
        """.trimIndent())
        assertNull(RequestValidator.validate(request.copy(modelId = "configured-llama-cpp")))
        assertContains(Json.encodeToString(request), "The player entered the lighthouse.")
    }

    @Test
    fun `diagnostic metrics omit measurements that were not supplied`() {
        val payload = Json { encodeDefaults = true }.encodeToString(
            StreamDiagnostic("llama_completion", "completed", metrics = DiagnosticMetrics(cacheTokens = 17))
        )

        assertContains(payload, "\"cacheTokens\":17")
        assertFalse(payload.contains("predictedTokens"))
        assertFalse(payload.contains("promptDurationMs"))
        assertFalse(payload.contains("totalDurationMs"))
    }

    @Test
    fun `stream done JSON exposes narrative focus without legacy names`() {
        val payload = Json.encodeToString(
            StreamDone(
                TurnMetrics(totalDurationMs = null, promptTokens = 2, cacheTokens = 1),
                WorldState(location = "Leuchtturm", narrativeFocus = "Der verrostete Mechanismus"),
            ),
        )

        assertContains(payload, "\"narrativeFocus\":\"Der verrostete Mechanismus\"")
        assertFalse(payload.contains("unresolvedSituation"))
        assertFalse(payload.contains("activeThread"))
    }

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
    fun `turn JSON carries signal-only fragment metadata and flag state`() {
        val request = Json.decodeFromString<TurnRequest>("""
            {"sessionId":"live-1","scenarioMarkdown":"# S","history":[],"playerAction":"look",
             "flags":{"visited":false},"signals":{"enter":[{"flag":"visited","value":true}]},
             "runtimeFragments":[{"matchText":"lighthouse","fragment":"","conditions":[{"flag":"visited","value":false}],"emittedSignals":["enter"]}]}
        """.trimIndent())

        assertNull(RequestValidator.validate(request.copy(modelId = "configured-llama-cpp")))
        assertEquals(mapOf("visited" to false), request.flags)
        assertEquals(listOf("enter"), request.runtimeFragments.single().emittedSignals)
    }

    @Test
    fun `request validation rejects undeclared flag and signal references`() {
        val request = TurnRequest(
            sessionId = "live-1",
            modelId = "configured-llama-cpp",
            scenarioMarkdown = "# S",
            history = emptyList(),
            playerAction = "look",
            runtimeFragments = listOf(RuntimeFragmentDefinition("lighthouse", "", listOf(FlagCondition("unknown", true)), listOf("missing"))),
        )

        assertNotNull(RequestValidator.validate(request))
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
    fun `request validation accepts narrative focus beyond the generation recommendation`() {
        val oversizedState = WorldState(narrativeFocus = "x".repeat(1_000))

        assertNull(
            RequestValidator.validate(
                TurnRequest("live-1", "configured-llama-cpp", 8081, "# S", emptyList(), "look", worldState = oversizedState),
            ),
        )
        assertNull(
            RequestValidator.validate(
                PrefillRequest("live-1", 0, "configured-llama-cpp", 8081, "# S", emptyList(), "look", worldState = oversizedState),
            ),
        )
        assertNull(
            RequestValidator.validate(
                WarmupRequest("live-1", "configured-llama-cpp", 8081, "# S", emptyList(), worldState = oversizedState),
            ),
        )
    }

    @Test
    fun `request validation accepts a narrative focus of 160 characters`() {
        val boundaryState = WorldState(narrativeFocus = "x".repeat(160))

        assertNull(
            RequestValidator.validate(
                TurnRequest("live-1", "configured-llama-cpp", 8081, "# S", emptyList(), "look", worldState = boundaryState),
            ),
        )
    }
}
