package de.aidadventure.server.embedding

import de.aidadventure.server.EmbeddingService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedTextEmbeddingTest {
    @Test
    fun defaultLimitSplitsTextBeyondTheTokenizerSafeWindow() = runTest {
        val text = "x".repeat(513)
        val left = text.take(256)
        val right = text.drop(256)
        val embedding = RecordingEmbedding(mapOf(left to floatArrayOf(1f), right to floatArrayOf(3f)))

        assertContentEquals(floatArrayOf((256f + 257f * 3f) / 513f), BoundedTextEmbedding(embedding).embed(text))
        assertEquals(listOf(left, right), embedding.inputs)
    }

    @Test
    fun embedsATextWithinTheLimitOnce() = runTest {
        val embedding = RecordingEmbedding(mapOf("short" to floatArrayOf(2f, 3f)))

        assertContentEquals(floatArrayOf(2f, 3f), BoundedTextEmbedding(embedding, 5).embed("short"))
        assertEquals(listOf("short"), embedding.inputs)
    }

    @Test
    fun combinesMidpointWordBoundaryHalvesByCharacterLength() = runTest {
        val embedding = RecordingEmbedding(
            mapOf("one two " to floatArrayOf(2f, 0f), "three four" to floatArrayOf(0f, 4f)),
        )

        val vector = BoundedTextEmbedding(embedding, 10).embed("one two three four")

        assertContentEquals(floatArrayOf(8f / 9f, 20f / 9f), vector)
        assertEquals(listOf("one two ", "three four"), embedding.inputs)
    }

    @Test
    fun splitsNoWhitespaceTextAtItsMidpoint() = runTest {
        val embedding = RecordingEmbedding(mapOf("abcd" to floatArrayOf(1f), "efgh" to floatArrayOf(3f)))

        assertContentEquals(floatArrayOf(2f), BoundedTextEmbedding(embedding, 4).embed("abcdefgh"))
        assertEquals(listOf("abcd", "efgh"), embedding.inputs)
    }

    @Test
    fun recursivelyCombinesLeavesByTheirCharacterLengths() = runTest {
        val embedding = RecordingEmbedding(
            mapOf("aa " to floatArrayOf(1f), "bb " to floatArrayOf(3f), "cc" to floatArrayOf(5f)),
        )

        assertContentEquals(floatArrayOf(11f / 4f), BoundedTextEmbedding(embedding, 3).embed("aa bb cc"))
        assertEquals(listOf("aa ", "bb ", "cc"), embedding.inputs)
    }

    @Test
    fun rejectsEmptyInputWithoutEmbeddingIt() = runTest {
        val embedding = RecordingEmbedding(emptyMap())

        assertFailsWith<IllegalArgumentException> { BoundedTextEmbedding(embedding).embed("") }
        assertEquals(emptyList(), embedding.inputs)
    }

    private class RecordingEmbedding(
        private val vectors: Map<String, FloatArray>,
    ) : EmbeddingService {
        override val dimension: Int = vectors.values.firstOrNull()?.size ?: 0
        val inputs = mutableListOf<String>()

        override suspend fun embed(text: String): FloatArray {
            inputs += text
            return requireNotNull(vectors[text]) { "Unexpected embedding input: $text" }
        }
    }
}
