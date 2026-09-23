package de.aidadventure.server.runtimefragments

import de.aidadventure.server.EmbeddingService
import de.aidadventure.server.embedding.BoundedTextEmbedding
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.RuntimeFragmentDefinition
import kotlin.math.sqrt

interface RuntimeFragmentResolver {
    suspend fun prepare(definitions: List<RuntimeFragmentDefinition>)

    suspend fun resolve(history: List<ChatEntry>, definitions: List<RuntimeFragmentDefinition>): RuntimeFragmentDefinition?

    suspend fun resolve(
        history: List<ChatEntry>,
        definitions: List<RuntimeFragmentDefinition>,
        narrativeFocus: String?,
    ): RuntimeFragmentDefinition? = resolve(history, definitions)
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
    ): RuntimeFragmentDefinition? = resolve(history, definitions, null)

    override suspend fun resolve(
        history: List<ChatEntry>,
        definitions: List<RuntimeFragmentDefinition>,
        narrativeFocus: String?,
    ): RuntimeFragmentDefinition? {
        if (definitions.isEmpty()) return null
        val componentTexts = narrativeFocus?.trim()?.takeIf { it.isNotEmpty() }?.let(::listOf)
            ?: latestVisiblePairComponents(history)
        if (componentTexts.isEmpty()) return null
        prepare(definitions)
        val componentVectors = componentTexts.map { boundedEmbedding.embed(it) }
        val dimension = componentVectors.first().size
        require(componentVectors.all { it.size == dimension }) { "Embedding vector dimensions differ" }
        val queryVector = l2Normalize(
            FloatArray(dimension) { index -> componentVectors.sumOf { it[index].toDouble() }.toFloat() / componentVectors.size },
        )
        return indexes.query(definitions, embedding, queryVector)
    }

    private fun latestVisiblePairComponents(history: List<ChatEntry>): List<String> {
        val visibleHistory = history.filter { it.role != RUNTIME_FRAGMENT_ROLE }
        val lastPlayer = visibleHistory.lastOrNull { it.role == PLAYER_ROLE } ?: return emptyList()
        val lastGameMaster = visibleHistory.lastOrNull { it.role == GAME_MASTER_ROLE } ?: return emptyList()
        return listOf(lastPlayer.content, lastGameMaster.content).filter { it.length >= MIN_COMPONENT_LENGTH }
    }

    override fun close() {
        (indexes as? AutoCloseable)?.close()
    }

    private companion object {
        const val PLAYER_ROLE = "player"
        const val GAME_MASTER_ROLE = "game-master"
        const val RUNTIME_FRAGMENT_ROLE = "runtime-fragment"
        const val MIN_COMPONENT_LENGTH = 10
    }
}

private fun l2Normalize(vector: FloatArray): FloatArray {
    val magnitude = sqrt(vector.sumOf { value -> value.toDouble() * value }.toFloat())
    require(magnitude > 0f) { "Embedding vector must not be zero" }
    return FloatArray(vector.size) { index -> vector[index] / magnitude }
}
