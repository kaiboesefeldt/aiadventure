package de.aidadventure.server.runtimefragments

import de.aidadventure.server.EmbeddingService
import de.aidadventure.server.embedding.BoundedTextEmbedding
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.RuntimeFragmentDefinition
import kotlin.math.sqrt

interface RuntimeFragmentResolver {
    suspend fun prepare(definitions: List<RuntimeFragmentDefinition>)

    suspend fun resolve(history: List<ChatEntry>, definitions: List<RuntimeFragmentDefinition>): RuntimeFragmentDefinition?
}

class DefaultRuntimeFragmentResolver private constructor(
    private val embedding: EmbeddingService,
    private val indexes: RuntimeFragmentIndex,
    @Suppress("UNUSED_PARAMETER") private val internalConstructorMarker: Unit,
) : RuntimeFragmentResolver, AutoCloseable {
    constructor(embedding: EmbeddingService) : this(embedding, RuntimeFragmentIndexRegistry(), Unit)

    internal constructor(
        embedding: EmbeddingService,
        indexes: RuntimeFragmentIndex,
    ) : this(embedding, indexes, Unit)

    private val boundedEmbedding = BoundedTextEmbedding(embedding)

    override suspend fun prepare(definitions: List<RuntimeFragmentDefinition>) {
        if (definitions.isNotEmpty()) indexes.prepare(definitions, embedding)
    }

    override suspend fun resolve(
        history: List<ChatEntry>,
        definitions: List<RuntimeFragmentDefinition>,
    ): RuntimeFragmentDefinition? {
        if (definitions.isEmpty()) return null
        val visibleHistory = history.filter { it.role != RUNTIME_FRAGMENT_ROLE }
        val lastPlayer = visibleHistory.lastOrNull { it.role == PLAYER_ROLE } ?: return null
        val lastGameMaster = visibleHistory.lastOrNull { it.role == GAME_MASTER_ROLE } ?: return null
        prepare(definitions)
        val playerVector = boundedEmbedding.embed(lastPlayer.content)
        val gameMasterVector = boundedEmbedding.embed(lastGameMaster.content)
        require(playerVector.size == gameMasterVector.size) { "Embedding vector dimensions differ" }
        val queryVector = l2Normalize(
            FloatArray(playerVector.size) { index -> (playerVector[index] + gameMasterVector[index]) / 2f },
        )
        return indexes.query(definitions, embedding, queryVector)
    }

    override fun close() {
        (indexes as? AutoCloseable)?.close()
    }

    private companion object {
        const val PLAYER_ROLE = "player"
        const val GAME_MASTER_ROLE = "game-master"
        const val RUNTIME_FRAGMENT_ROLE = "runtime-fragment"
    }
}

private fun l2Normalize(vector: FloatArray): FloatArray {
    val magnitude = sqrt(vector.sumOf { value -> value.toDouble() * value }.toFloat())
    require(magnitude > 0f) { "Embedding vector must not be zero" }
    return FloatArray(vector.size) { index -> vector[index] / magnitude }
}
