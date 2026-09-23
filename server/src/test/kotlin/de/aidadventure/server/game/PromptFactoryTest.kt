package de.aidadventure.server.game

import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.FragmentRelevanceRequest
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.WarmupRequest
import de.aidadventure.server.worldstate.WorldState
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptFactoryTest {
    @Test
    fun `build keeps immutable prefix before history and action`() {
        val prompt = PromptFactory().build(
            TurnRequest("live-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("player", "look")), "open door"),
        )

        assertEquals(listOf("system", "user", "user"), prompt.map { it.type().name.lowercase() })
        assertEquals("# Harbor", (prompt[0] as SystemMessage).text())
        assertEquals("look", (prompt[1] as UserMessage).singleText())
        assertEquals("open door", (prompt[2] as UserMessage).singleText())
    }

    @Test
    fun `initial situation is a one-time context immediately before introduction action`() {
        val request = TurnRequest(
            sessionId = "live-1", scenarioMarkdown = "# Harbor", history = emptyList(),
            playerAction = "begin adventure", initialSituation = "The harbor-master greets the player.",
        )
        val prompt = PromptFactory().build(request)

        assertEquals(listOf("system", "user", "user"), prompt.map { it.type().name.lowercase() })
        assertEquals(
            "INITIAL SITUATION (one-time opening context; not player dialogue):\nThe harbor-master greets the player.",
            (prompt[1] as UserMessage).singleText(),
        )
        assertEquals("begin adventure", (prompt[2] as UserMessage).singleText())
        assertFalse((prompt[0] as SystemMessage).text().contains("harbor-master"))
    }

    @Test
    fun `ordinary turn and warmup omit initial situation context`() {
        val factory = PromptFactory()
        val ordinary = factory.build(TurnRequest("live-1", "test-model", 8081, "# Harbor", emptyList(), "look"))
        val warmup = factory.buildWarmup(WarmupRequest("live-1", "test-model", 8081, "# Harbor", emptyList()))

        assertEquals(listOf("system", "user"), ordinary.map { it.type().name.lowercase() })
        assertEquals(listOf("system"), warmup.map { it.type().name.lowercase() })
    }

    @Test
    fun `build rejects unknown history roles`() {
        assertFailsWith<IllegalArgumentException> {
            PromptFactory().build(
                TurnRequest("live-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("narrator", "look")), "open door"),
            )
        }
    }

    @Test
    fun `runtime fragment history maps to labeled generated context`() {
        val prompt = PromptFactory().build(
            TurnRequest("live-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("runtime-fragment", "keep quiet")), "look"),
        )

        assertEquals(
            "GM GUIDANCE / RUNTIME CONTEXT (generated context; not a player statement):\nkeep quiet",
            (prompt[1] as UserMessage).singleText(),
        )
    }

    @Test
    fun `buildWarmup keeps immutable prefix and history without a synthetic player action`() {
        val prompt = PromptFactory().buildWarmup(
            WarmupRequest("live-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("player", "look"))),
        )

        assertEquals(listOf("system", "user"), prompt.map { it.type().name.lowercase() })
        assertEquals("# Harbor", (prompt[0] as SystemMessage).text())
        assertEquals("look", (prompt[1] as UserMessage).singleText())
    }

    @Test
    fun `buildRelevance appends the candidate key as labeled analysis context before its fixed analysis action`() {
        val prompt = PromptFactory().buildRelevance(
            FragmentRelevanceRequest(
                sessionId = "live-1",
                modelId = "test-model",
                llamaCppPort = 8081,
                scenarioMarkdown = "# Harbor",
                history = listOf(ChatEntry("player", "look")),
                candidateKey = "lighthouse",
            ),
        )

        assertEquals(listOf("system", "user", "user", "user"), prompt.map { it.type().name.lowercase() })
        assertEquals("ANALYSIS CONTEXT (candidate key; not a player statement):\nlighthouse", (prompt[2] as UserMessage).singleText())
        assertEquals(
            "Rate how well the preceding text fits the current game situation. Return a single ASCII digit between 0 (=no fit) and 9 (=perfect fit).",
            (prompt[3] as UserMessage).singleText(),
        )
    }

    @Test
    fun `prompt builders omit delimited YAML frontmatter from scenario context`() {
        val scenario = """
            ---
            runtime-fragments:
              lighthouse: "Reveal a clue"
            locations:
              Kai: "Der zentrale Hafenumschlagplatz"
              Leuchtturm: "Alter Turm auf der Mole"
            ---
            # Der Leuchtturm von Nebelhafen
            """.trimIndent()
        val factory = PromptFactory()

        assertEquals(
            "# Der Leuchtturm von Nebelhafen",
            (factory.build(TurnRequest("live-1", "test-model", 8081, scenario, emptyList(), "look"))[0] as SystemMessage).text(),
        )
        assertEquals(
            "# Der Leuchtturm von Nebelhafen",
            (factory.buildWarmup(WarmupRequest("live-1", "test-model", 8081, scenario, emptyList()))[0] as SystemMessage).text(),
        )
        assertEquals(
            "# Der Leuchtturm von Nebelhafen",
            (factory.buildRelevance(FragmentRelevanceRequest("live-1", "test-model", 8081, scenario, emptyList(), "lighthouse"))[0] as SystemMessage).text(),
        )
    }

    @Test
    fun `new game warmup is a prefix of its first turn`() {
        val factory = PromptFactory()
        val warmup = factory.buildWarmup(WarmupRequest("live-1", "test-model", 8081, "# Harbor", emptyList()))
        val firstTurn = factory.build(TurnRequest("live-1", "test-model", 8081, "# Harbor", emptyList(), "enter the inn"))

        assertCachePrefix(warmup, firstTurn)
    }

    @Test
    fun `resumed game warmup is a prefix of its first turn`() {
        val factory = PromptFactory()
        val history = listOf(ChatEntry("player", "inspect the pier"), ChatEntry("game-master", "Fog covers the water."))
        val worldState = WorldState("Küche", "Finde den Saboteur")
        val warmup = factory.buildWarmup(WarmupRequest("saved-1", "test-model", 8081, "# Harbor", history, worldState = worldState))
        val firstTurn = factory.build(TurnRequest("saved-1", "test-model", 8081, "# Harbor", history, "follow the bell", worldState = worldState))

        assertCachePrefix(warmup, firstTurn)
    }

    @Test
    fun `next turn extends the completed turn cache chain`() {
        val factory = PromptFactory()
        val completedTurn = factory.build(TurnRequest("live-1", "test-model", 8081, "# Harbor", emptyList(), "inspect the pier"))
        val persistedHistory = listOf(
            ChatEntry("player", "inspect the pier"),
            ChatEntry("game-master", "Fog covers the water."),
        )
        val nextTurn = factory.build(TurnRequest("live-1", "test-model", 8081, "# Harbor", persistedHistory, "follow the bell"))

        assertCachePrefix(completedTurn + AiMessage.from("Fog covers the water."), nextTurn)
    }

    @Test
    fun `accepted reranking warmup is a prefix of the next turn without temporary analysis context`() {
        val factory = PromptFactory()
        val history = listOf(
            ChatEntry("player", "inspect the pier"),
            ChatEntry("game-master", "Fog covers the water."),
            ChatEntry("runtime-fragment", "The keeper avoids the lighthouse."),
        )
        val warmup = factory.buildWarmup(WarmupRequest("live-1", "test-model", 8081, "# Harbor", history))
        val nextTurn = factory.build(TurnRequest("live-1", "test-model", 8081, "# Harbor", history, "follow the bell"))

        assertCachePrefix(warmup, nextTurn)
        assertFalse(warmup.any { segment(it).second == "lighthouse" || segment(it).second == PromptFactory.FRAGMENT_RELEVANCE_PROMPT })
    }

    @Test
    fun `rejected reranking warmup is a prefix of the next turn without temporary analysis context`() {
        val factory = PromptFactory()
        val history = listOf(
            ChatEntry("player", "inspect the pier"),
            ChatEntry("game-master", "Fog covers the water."),
        )
        val warmup = factory.buildWarmup(WarmupRequest("live-1", "test-model", 8081, "# Harbor", history))
        val nextTurn = factory.build(TurnRequest("live-1", "test-model", 8081, "# Harbor", history, "follow the bell"))

        assertCachePrefix(warmup, nextTurn)
        assertFalse(warmup.any { segment(it).second == "lighthouse" || segment(it).second == PromptFactory.FRAGMENT_RELEVANCE_PROMPT })
    }

    private fun assertCachePrefix(expectedPrefix: List<ChatMessage>, actual: List<ChatMessage>) {
        assertEquals(expectedPrefix.map(::segment), actual.take(expectedPrefix.size).map(::segment))
    }

    private fun segment(message: ChatMessage): Pair<String, String> = when (message) {
        is SystemMessage -> "system" to message.text()
        is UserMessage -> "user" to message.singleText()
        is AiMessage -> "assistant" to message.text()
        else -> error("Unsupported cache message: ${message.type()}")
    }
}
