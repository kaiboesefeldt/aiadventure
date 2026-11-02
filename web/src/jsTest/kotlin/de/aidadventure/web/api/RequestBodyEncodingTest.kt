package de.aidadventure.web.api

import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.model.WorldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RequestBodyEncodingTest {
    @Test fun `all request mappers preserve runtime fragment assignments`() {
        val scenario = Scenario("scenario", "Tower", "---\nflags: {visited: false}\nruntime-fragments:\n  tower:\n    set-flag: {visited: true}\n---\n# Tower", 1)
        val session = GameSession("session", scenario.id, "Tower", messages = emptyList(), createdAt = 1, updatedAt = 1)
        val bodies = listOf(
            encodeTurnRequest(TurnRequestMapper.map(session, scenario, "enter", "session")),
            encodePrefillRequest(PrefillRequestMapper.map(session, scenario, "session", 1, "enter")),
            encodeWarmupRequest(WarmupRequestMapper.map(session, scenario)))
        bodies.forEach { encoded ->
            val fragment = Json.parseToJsonElement(encoded).jsonObject["runtimeFragments"]!!.jsonArray.single().jsonObject
            assertEquals(Json.parseToJsonElement("""[{"flag":"visited","value":true}]"""), fragment["setFlags"])
        }
    }

    @Test fun `all request mappers preserve location only predicates and location exclusions`() {
        val scenario = Scenario("scenario", "Harbor", """---
flags: {}
signals:
  visit:
    condition: {location: Leuchtturm}
    triggers: [{emit-text: Arrival}]
runtime-fragments:
  condition:
    - location: Leuchtturm
      then: {emit-text: Tower, signal: visit}
    - else: {emit-text: Elsewhere}
---
# Harbor
""", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
        val bodies = listOf(
            encodeTurnRequest(TurnRequestMapper.map(session, scenario, "enter", "session")),
            encodePrefillRequest(PrefillRequestMapper.map(session, scenario, "session", 1, "enter")),
            encodeWarmupRequest(WarmupRequestMapper.map(session, scenario)))
        bodies.forEach { encoded ->
            val body = Json.parseToJsonElement(encoded).jsonObject
            val rules = body["runtimeFragments"]!!.jsonArray.map { it.jsonObject }
            val expected = Json.parseToJsonElement("""{"location":"Leuchtturm"}""")
            assertEquals(expected, rules[0]["conditions"]!!.jsonArray.single())
            assertEquals(expected, rules[1]["excludedConditions"]!!.jsonArray.single().jsonArray.single())
            assertEquals(expected, body["signalChecks"]!!.jsonArray.single().jsonObject["conditions"]!!.jsonArray.single())
        }
    }

    @Test fun `all request mappers preserve direct rules and else exclusions`() {
        val scenario = Scenario("scenario", "Harbor", """---
flags: {ready: false}
signals:
  visit:
    triggers:
      - emit-text: Signal guidance
runtime-fragments:
  condition:
    - ready: true
      then: {emit-text: Ready, signal: visit}
    - else:
        emit-text: Waiting
        harbor: Retrieved
---
# Harbor
""", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
        val bodies = listOf(
            encodeTurnRequest(TurnRequestMapper.map(session, scenario, "enter", "session")),
            encodePrefillRequest(PrefillRequestMapper.map(session, scenario, "session", 1, "enter")),
            encodeWarmupRequest(WarmupRequestMapper.map(session, scenario)),
        )
        bodies.forEach { encoded ->
            val body = Json.parseToJsonElement(encoded).jsonObject
            val rules = body["runtimeFragments"]!!.jsonArray.map { it.jsonObject }
            assertEquals("true", rules.first()["direct"]?.jsonPrimitive?.content)
            assertEquals("Waiting", rules[1]["fragment"]!!.jsonPrimitive.content)
            assertEquals("ready", rules[1]["excludedConditions"]!!.jsonArray.single().jsonArray.single().jsonObject["flag"]!!.jsonPrimitive.content)
            assertEquals("harbor", rules[2]["matchText"]!!.jsonPrimitive.content)
            assertEquals(null, body["signalChecks"]!!.jsonArray.single().jsonObject["eventCheck"])
        }
    }

    @Test fun `all request mappers serialize explicit event checks and keep assignments`() {
        val scenario = Scenario("scenario", "Harbor", "---\nflags: {visited: false}\nsignals:\n  visit:\n    event-check: The player actually entered.\n    condition: {visited: false}\n    triggers:\n      - emit-text: First hint\n      - set-flag: {visited: true}\n      - emit-text: Second hint\n---\n# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
        val bodies = listOf(
            encodeTurnRequest(TurnRequestMapper.map(session, scenario, "enter", "session")),
            encodePrefillRequest(PrefillRequestMapper.map(session, scenario, "session", 1, "enter")),
            encodeWarmupRequest(WarmupRequestMapper.map(session, scenario)),
        )
        bodies.forEach { encoded ->
            val body = Json.parseToJsonElement(encoded).jsonObject
            val check = body["signalChecks"]!!.jsonArray.single().jsonObject
            assertEquals("visit", check["name"]!!.jsonPrimitive.content)
            assertEquals(listOf("First hint", "Second hint"), check["emittedTexts"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("The player actually entered.", check["eventCheck"]!!.jsonPrimitive.content)
            assertEquals("visited", check["conditions"]!!.jsonArray.single().jsonObject["flag"]!!.jsonPrimitive.content)
            assertEquals("false", check["conditions"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
            assertEquals("true", body["signals"]!!.jsonObject["visit"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `turn requests use their concrete serializer`() {
        val encoded = encodeTurnRequest(
            TurnRequest("# Scenario", emptyList(), "look", "session-1"),
        )

        assertEquals("session-1", Json.parseToJsonElement(encoded).jsonObject["sessionId"]?.jsonPrimitive?.content)
        assertEquals(null, Json.parseToJsonElement(encoded).jsonObject["modelId"])
        assertEquals(null, Json.parseToJsonElement(encoded).jsonObject["llamaCppPort"])
    }

    @Test
    fun `prefill requests use their concrete serializer`() {
        val encoded = encodePrefillRequest(
            PrefillRequest("session-1", 3, "# Scenario", emptyList(), "look"),
        )

        assertEquals("3", Json.parseToJsonElement(encoded).jsonObject["revision"]?.jsonPrimitive?.content)
    }

    @Test
    fun `warmup requests contain saved context but no player action`() {
        val encoded = encodeWarmupRequest(
            WarmupRequest("session-1", "# Scenario", emptyList()),
        )

        val body = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("session-1", body["sessionId"]?.jsonPrimitive?.content)
        assertEquals(null, body["playerAction"])
    }

    @Test
    fun `turn requests encode runtime fragments`() {
        val encoded = encodeTurnRequest(
            TurnRequest("# Scenario", emptyList(), "look", "session-1", listOf(RuntimeFragmentDefinition("lighthouse", "Mira remembers it"))),
        )

        val body = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("Mira remembers it", body["runtimeFragments"]!!.jsonArray.single().jsonObject["fragment"]!!.jsonPrimitive.content)
    }

    @Test
    fun `request mappers strip frontmatter and carry its fragments`() {
        val scenario = Scenario(
            "scenario-1",
            "Harbor",
            "---\nruntime-fragments:\n  lighthouse: Mira remembers it\ninitial-situation:\n  world-state:\n    location: Bar\n  setting: The keeper greets the player.\n---\n# Harbor",
            1,
        )
        val session = GameSession("session-1", scenario.id, "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1, worldState = WorldState("Bar"))

        val turn = TurnRequestMapper.map(session, scenario, "look", "live-1")
        val introduction = TurnRequestMapper.map(session, scenario, "begin", "live-1", includeInitialSituation = true)
        val prefill = PrefillRequestMapper.map(session, scenario, "live-1", 1, "look")
        val warmup = WarmupRequestMapper.map(session, scenario)

        assertEquals("# Harbor", turn.scenarioMarkdown)
        assertEquals(listOf(RuntimeFragmentDefinition("lighthouse", "Mira remembers it")), turn.runtimeFragments)
        assertEquals("", turn.initialSituation)
        assertEquals("The keeper greets the player.", introduction.initialSituation)
        assertEquals("Bar", introduction.worldState.location)
        assertEquals(turn.scenarioMarkdown, prefill.scenarioMarkdown)
        assertEquals(turn.runtimeFragments, prefill.runtimeFragments)
        assertEquals(turn.scenarioMarkdown, warmup.scenarioMarkdown)
        assertEquals(turn.runtimeFragments, warmup.runtimeFragments)
        assertEquals(null, Json.parseToJsonElement(encodePrefillRequest(prefill)).jsonObject["initialSituation"])
    }
}
