package de.aidadventure.server.runtimefragments

import de.aidadventure.server.EmbeddingService
import de.aidadventure.server.embedding.BoundedTextEmbedding.Companion.MAX_EMBEDDING_CHARACTERS
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.RuntimeFragmentDefinition
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeFragmentResolverTest {
    @Test
    fun resolverUsesNormalizedEqualWeightOfLatestVisiblePair() = runTest {
        val embedding = RecordingEmbeddingService(
            mapOf(
                "player" to floatArrayOf(3f, 0f),
                "answer" to floatArrayOf(0f, 4f),
                "match" to floatArrayOf(1f, 1f),
            ),
        )
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)
        val resolver = DefaultRuntimeFragmentResolver(embedding, index)

        val resolved = resolver.resolve(
            listOf(ChatEntry("player", "player"), ChatEntry("game-master", "answer")),
            listOf(definition),
        )

        assertEquals(definition, resolved)
        assertEquals(listOf("match", "player", "answer"), embedding.inputs)
        assertContentEquals(floatArrayOf(0.6f, 0.8f), index.queryVectors.single())
    }

    @Test
    fun resolverBoundsLongPlayerInputBeforeEqualWeightQueryComposition() = runTest {
        val player = "a".repeat(MAX_EMBEDDING_CHARACTERS + 1)
        val firstLeaf = player.substring(0, player.length / 2)
        val secondLeaf = player.substring(player.length / 2)
        val embedding = RecordingEmbeddingService(
            mapOf(
                "match" to floatArrayOf(1f, 1f),
                firstLeaf to floatArrayOf(2f, 0f),
                secondLeaf to floatArrayOf(0f, 2f),
                "answer" to floatArrayOf(0f, 4f),
            ),
        )
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)
        val resolver = DefaultRuntimeFragmentResolver(embedding, index)

        resolver.resolve(
            listOf(ChatEntry("player", player), ChatEntry("game-master", "answer")),
            listOf(definition),
        )

        assert(!embedding.inputs.contains("$player\nanswer"))
        val playerVector = floatArrayOf(
            2f * firstLeaf.length / player.length,
            2f * secondLeaf.length / player.length,
        )
        val outerAverage = floatArrayOf(playerVector[0] / 2f, (playerVector[1] + 4f) / 2f)
        val magnitude = kotlin.math.sqrt(
            outerAverage.sumOf { value -> value.toDouble() * value }.toFloat(),
        )
        assertContentEquals(
            FloatArray(outerAverage.size) { index -> outerAverage[index] / magnitude },
            index.queryVectors.single(),
        )
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

    private class RecordingEmbeddingService(
        private val vectors: Map<String, FloatArray> = emptyMap(),
    ) : EmbeddingService {
        override val dimension = 2
        val inputs = mutableListOf<String>()

        override suspend fun embed(text: String): FloatArray {
            inputs += text
            return vectors[text] ?: floatArrayOf(1f, 0f)
        }
    }

    private class RecordingIndex(
        private val resolved: RuntimeFragmentDefinition,
    ) : RuntimeFragmentIndex {
        val queryVectors = mutableListOf<FloatArray>()

        override suspend fun prepare(
            definitions: List<RuntimeFragmentDefinition>,
            embedding: EmbeddingService,
        ) {
            definitions.forEach { embedding.embed(it.matchText) }
        }

        override suspend fun query(
            definitions: List<RuntimeFragmentDefinition>,
            embedding: EmbeddingService,
            queryVector: FloatArray,
        ): RuntimeFragmentDefinition? {
            queryVectors += queryVector
            return resolved
        }
    }
}
