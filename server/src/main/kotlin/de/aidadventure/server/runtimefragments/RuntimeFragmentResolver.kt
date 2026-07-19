package de.aidadventure.server.runtimefragments

import de.aidadventure.server.EmbeddingService
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.RuntimeFragmentDefinition

interface RuntimeFragmentResolver {
    suspend fun prepare(definitions: List<RuntimeFragmentDefinition>)

    suspend fun resolve(history: List<ChatEntry>, definitions: List<RuntimeFragmentDefinition>): RuntimeFragmentDefinition?
}

class DefaultRuntimeFragmentResolver(
    private val embedding: EmbeddingService,
    private val indexes: RuntimeFragmentIndexRegistry = RuntimeFragmentIndexRegistry(),
) : RuntimeFragmentResolver, AutoCloseable {
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
        val query = "${lastPlayer.content}\n${lastGameMaster.content}"
        prepare(definitions)
        return indexes.query(definitions, embedding, embedding.embed(query))
    }

    override fun close() = indexes.close()

    private companion object {
        const val PLAYER_ROLE = "player"
        const val GAME_MASTER_ROLE = "game-master"
        const val RUNTIME_FRAGMENT_ROLE = "runtime-fragment"
    }
}
