package de.aidadventure.server.embedding

import de.aidadventure.server.EmbeddingService
import kotlin.math.abs

class BoundedTextEmbedding(
    private val embedding: EmbeddingService,
    private val maxEmbeddingCharacters: Int = MAX_EMBEDDING_CHARACTERS,
) {
    init {
        require(maxEmbeddingCharacters >= 1) { "maxEmbeddingCharacters must be positive" }
    }

    suspend fun embed(text: String): FloatArray {
        require(text.isNotEmpty()) { "text must not be empty" }
        if (text.length <= maxEmbeddingCharacters) return embedding.embed(text)

        val (left, right) = split(text)
        val leftVector = embed(left)
        val rightVector = embed(right)
        require(leftVector.size == rightVector.size) { "Embedding vector dimensions differ" }

        val totalLength = left.length + right.length
        return FloatArray(leftVector.size) { index ->
            (leftVector[index] * left.length + rightVector[index] * right.length) / totalLength
        }
    }

    private fun split(text: String): Pair<String, String> {
        val midpoint = text.length / 2
        val splitAt = text.indices
            .filter { it in 1 until text.length && text[it - 1].isWhitespace() }
            .minByOrNull { abs(it - midpoint) }
            ?: midpoint
        return text.substring(0, splitAt) to text.substring(splitAt)
    }

    companion object {
        const val MAX_EMBEDDING_CHARACTERS = 4_000
    }
}
