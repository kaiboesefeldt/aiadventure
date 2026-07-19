package de.aidadventure.server.runtimefragments

import de.aidadventure.server.EmbeddingService
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.RuntimeFragmentDefinition
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeFragmentResolverTest {
    @Test
    fun resolverEmbedsLatestVisiblePairAndReturnsTopFragment() = runTest {
        val embedding = RecordingEmbeddingService()
        val resolver = DefaultRuntimeFragmentResolver(embedding)

        val resolved = resolver.resolve(
            listOf(ChatEntry("player", "lighthouse"), ChatEntry("game-master", "sunset")),
            listOf(RuntimeFragmentDefinition("lighthouse", "Mira avoids it.")),
        )

        assertEquals(RuntimeFragmentDefinition("lighthouse", "Mira avoids it."), resolved)
        assertEquals("lighthouse\nsunset", embedding.inputs.last())
    }

    @Test
    fun firstTurnReturnsNullAndHiddenHistoryIsIgnored() = runTest {
        val resolver = DefaultRuntimeFragmentResolver(RecordingEmbeddingService())

        assertNull(resolver.resolve(listOf(ChatEntry("player", "look")), definitions()))
        assertEquals(
            RuntimeFragmentDefinition("match", "fragment"),
            resolver.resolve(
                listOf(
                    ChatEntry("player", "match"),
                    ChatEntry("game-master", "answer"),
                    ChatEntry("runtime-fragment", "old"),
                ),
                definitions(),
            ),
        )
    }

    @Test
    fun equalScoresUseStableDocumentIdTieBreaker() = runTest {
        val resolver = DefaultRuntimeFragmentResolver(RecordingEmbeddingService())
        val definitions = listOf(
            RuntimeFragmentDefinition("first", "one"),
            RuntimeFragmentDefinition("second", "two"),
        )

        val resolved = resolver.resolve(
            listOf(ChatEntry("player", "anything"), ChatEntry("game-master", "anything")),
            definitions,
        )

        assertEquals(definitions.minBy { RuntimeFragmentIndexRegistry.documentIdFor(it) }, resolved)
    }

    private fun definitions() = listOf(RuntimeFragmentDefinition("match", "fragment"))

    private class RecordingEmbeddingService : EmbeddingService {
        override val dimension = 2
        val inputs = mutableListOf<String>()

        override suspend fun embed(text: String): FloatArray {
            inputs += text
            return floatArrayOf(1f, 0f)
        }
    }
}
