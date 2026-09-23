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
import kotlin.test.assertTrue

class RuntimeFragmentResolverTest {
    @Test
    fun resolverUsesNarrativeFocusAsQueryWhenPresent() = runTest {
        val focus = "The saboteur's plan is centered on the lighthouse."
        val embedding = RecordingEmbeddingService(
            mapOf(
                "match" to floatArrayOf(1f, 1f),
                focus to floatArrayOf(0f, 5f),
                "player input" to floatArrayOf(5f, 0f),
                "answer reply" to floatArrayOf(5f, 0f),
            ),
        )
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)

        DefaultRuntimeFragmentResolver(embedding, index).resolve(
            listOf(ChatEntry("player", "player input"), ChatEntry("game-master", "answer reply")),
            listOf(definition),
            narrativeFocus = focus,
        )

        assertEquals(listOf("match", focus), embedding.inputs)
        assertContentEquals(floatArrayOf(0f, 1f), index.queryVectors.single())
    }

    @Test
    fun resolverWeightsNarrativeFocusTwiceAndAddsNormalizedLocationContext() = runTest {
        val focus = "The saboteur is hiding at the lighthouse."
        val locationContext = "CURRENT LOCATION: Leuchtturm\nLOCATION DESCRIPTION: Alter Turm auf der Mole"
        val embedding = RecordingEmbeddingService(
            mapOf(
                "match" to floatArrayOf(1f, 1f),
                focus to floatArrayOf(0f, 2f),
                locationContext to floatArrayOf(3f, 0f),
            ),
        )
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)

        DefaultRuntimeFragmentResolver(embedding, index).resolve(
            history = emptyList(),
            definitions = listOf(definition),
            narrativeFocus = focus,
            location = "  Leuchtturm\n",
            locationDescription = "  Alter   Turm\nauf der Mole  ",
        )

        assertEquals(listOf("match", focus, locationContext), embedding.inputs)
        assertVectorApproximately(floatArrayOf(0.6f, 0.8f), index.queryVectors.single())
    }

    @Test
    fun resolverAddsLocationToHistoryFallbackWithMissingOrBlankDescription() = runTest {
        val locationContext = "CURRENT LOCATION: Leuchtturm"
        listOf(null, " \n ").forEach { description ->
            val embedding = RecordingEmbeddingService(
                mapOf(
                    "match" to floatArrayOf(1f, 1f),
                    "player input" to floatArrayOf(2f, 0f),
                    "answer reply" to floatArrayOf(0f, 2f),
                    locationContext to floatArrayOf(0f, 4f),
                ),
            )
            val definition = RuntimeFragmentDefinition("match", "fragment")
            val index = RecordingIndex(definition)

            DefaultRuntimeFragmentResolver(embedding, index).resolve(
                history = listOf(ChatEntry("player", "player input"), ChatEntry("game-master", "answer reply")),
                definitions = listOf(definition),
                narrativeFocus = "  ",
                location = " Leuchtturm ",
                locationDescription = description,
            )

            assertEquals(listOf("match", "player input", "answer reply", locationContext), embedding.inputs)
            assertVectorApproximately(floatArrayOf(1f / kotlin.math.sqrt(10f), 3f / kotlin.math.sqrt(10f)), index.queryVectors.single())
        }
    }

    @Test
    fun resolverCapsNormalizedLocationDescription() = runTest {
        val description = "x".repeat(400)
        val expectedContext = "CURRENT LOCATION: Kai\nLOCATION DESCRIPTION: ${description.take(300)}"
        val embedding = RecordingEmbeddingService(mapOf("match" to floatArrayOf(1f, 1f), expectedContext to floatArrayOf(1f, 0f)))
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)

        DefaultRuntimeFragmentResolver(embedding, index).resolve(
            history = emptyList(),
            definitions = listOf(definition),
            narrativeFocus = null,
            location = "Kai",
            locationDescription = description,
        )

        assertEquals(listOf("match", expectedContext), embedding.inputs)
    }

    @Test
    fun resolverFallsBackToLatestVisiblePairWhenNarrativeFocusIsBlank() = runTest {
        val embedding = RecordingEmbeddingService(
            mapOf(
                "match" to floatArrayOf(1f, 1f),
                "player input" to floatArrayOf(3f, 0f),
                "answer reply" to floatArrayOf(0f, 4f),
            ),
        )
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)

        DefaultRuntimeFragmentResolver(embedding, index).resolve(
            listOf(ChatEntry("player", "player input"), ChatEntry("game-master", "answer reply")),
            listOf(definition),
            narrativeFocus = "  ",
        )

        assertEquals(listOf("match", "player input", "answer reply"), embedding.inputs)
        assertContentEquals(floatArrayOf(0.6f, 0.8f), index.queryVectors.single())
    }

    @Test
    fun resolverUsesNormalizedEqualWeightOfLatestVisiblePair() = runTest {
        val embedding = RecordingEmbeddingService(
            mapOf(
                "player input" to floatArrayOf(3f, 0f),
                "answer reply" to floatArrayOf(0f, 4f),
                "match" to floatArrayOf(1f, 1f),
            ),
        )
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)
        val resolver = DefaultRuntimeFragmentResolver(embedding, index)

        val resolved = resolver.resolve(
            listOf(ChatEntry("player", "player input"), ChatEntry("game-master", "answer reply")),
            listOf(definition),
        )

        assertEquals(definition, resolved)
        assertEquals(listOf("match", "player input", "answer reply"), embedding.inputs)
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
                "answer reply" to floatArrayOf(0f, 4f),
            ),
        )
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)
        val resolver = DefaultRuntimeFragmentResolver(embedding, index)

        resolver.resolve(
            listOf(ChatEntry("player", player), ChatEntry("game-master", "answer reply")),
            listOf(definition),
        )

        assert(!embedding.inputs.contains("$player\nanswer reply"))
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
    fun resolverIgnoresShortPlayerComponent() = runTest {
        val embedding = RecordingEmbeddingService(
            mapOf(
                "match" to floatArrayOf(1f, 1f),
                "The lighthouse keeper turns away from the door." to floatArrayOf(0f, 4f),
            ),
        )
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)
        val resolver = DefaultRuntimeFragmentResolver(embedding, index)

        val resolved = resolver.resolve(
            listOf(
                ChatEntry("player", "ja"),
                ChatEntry("game-master", "The lighthouse keeper turns away from the door."),
            ),
            listOf(definition),
        )

        assertEquals(definition, resolved)
        assertEquals(listOf("match", "The lighthouse keeper turns away from the door."), embedding.inputs)
        assertContentEquals(floatArrayOf(0f, 1f), index.queryVectors.single())
    }

    @Test
    fun resolverSkipsQueryWhenBothVisibleComponentsAreShort() = runTest {
        val embedding = RecordingEmbeddingService(mapOf("match" to floatArrayOf(1f, 1f)))
        val definition = RuntimeFragmentDefinition("match", "fragment")
        val index = RecordingIndex(definition)

        assertNull(
            DefaultRuntimeFragmentResolver(embedding, index).resolve(
                listOf(ChatEntry("player", "2"), ChatEntry("game-master", "ja")),
                listOf(definition),
            ),
        )
        assertEquals(emptyList(), embedding.inputs)
        assertEquals(emptyList(), index.queryVectors)
    }

    @Test
    fun firstTurnReturnsNullAndHiddenHistoryIsIgnored() = runTest {
        val resolver = DefaultRuntimeFragmentResolver(RecordingEmbeddingService())

        assertNull(resolver.resolve(listOf(ChatEntry("player", "look")), definitions()))
        assertEquals(
            RuntimeFragmentDefinition("match", "fragment"),
            resolver.resolve(
                listOf(
                    ChatEntry("player", "match action"),
                    ChatEntry("game-master", "answer reply"),
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
            listOf(ChatEntry("player", "anything long"), ChatEntry("game-master", "anything long")),
            definitions,
        )

        assertEquals(definitions.minBy { RuntimeFragmentIndexRegistry.documentIdFor(it) }, resolved)
    }

    private fun definitions() = listOf(RuntimeFragmentDefinition("match", "fragment"))

    private fun assertVectorApproximately(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertTrue(kotlin.math.abs(expected[index] - actual[index]) < 0.000001f)
        }
    }

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
