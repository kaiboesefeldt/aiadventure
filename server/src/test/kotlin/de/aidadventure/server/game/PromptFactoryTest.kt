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

class PromptFactoryTest {
    @Test
    fun `build keeps immutable prefix before history and action`() {
        val prompt = PromptFactory().build(
            TurnRequest("live-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("player", "look")), "open door"),
        )

        assertEquals(listOf("system", "system", "user", "user"), prompt.map { it.type().name.lowercase() })
        assertEquals("# Harbor", (prompt[1] as SystemMessage).text())
        assertEquals("look", (prompt[2] as UserMessage).singleText())
        assertEquals("open door", (prompt[3] as UserMessage).singleText())
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
    fun `runtime fragment history maps to a system message`() {
        val prompt = PromptFactory().build(
            TurnRequest("live-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("runtime-fragment", "keep quiet")), "look"),
        )

        assertEquals("keep quiet", (prompt[2] as SystemMessage).text())
    }

    @Test
    fun `buildWarmup keeps immutable prefix and history without a synthetic player action`() {
        val prompt = PromptFactory().buildWarmup(
            WarmupRequest("live-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("player", "look"))),
        )

        assertEquals(listOf("system", "system", "user"), prompt.map { it.type().name.lowercase() })
        assertEquals("# Harbor", (prompt[1] as SystemMessage).text())
        assertEquals("look", (prompt[2] as UserMessage).singleText())
    }

    @Test
    fun `buildRelevance appends the candidate key as system context before its fixed analysis action`() {
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

        assertEquals(listOf("system", "system", "user", "system", "user"), prompt.map { it.type().name.lowercase() })
        assertEquals("lighthouse", (prompt[3] as SystemMessage).text())
        assertEquals(
            "Rate how well the preceding text fits the current game situation. Return a single ASCII digit between 0 (=no fit) and 9 (=perfect fit).",
            (prompt[4] as UserMessage).singleText(),
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
