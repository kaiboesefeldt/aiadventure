package de.aidadventure.server.worldstate

import java.text.Normalizer
import kotlinx.serialization.Serializable

@Serializable
data class WorldState(val revision: Long = 0, val entities: Map<String, WorldEntity> = emptyMap(), val facts: Map<String, String> = emptyMap())

@Serializable
data class WorldEntity(val kind: String, val name: String, val summary: String)

@Serializable
data class WorldStateProposal(
    val baseRevision: Long,
    val entities: List<WorldStateEntityProposal> = emptyList(),
    val facts: List<WorldStateFactProposal> = emptyList(),
)

@Serializable
data class WorldStateEntityProposal(val name: String, val kind: String, val summary: String)

@Serializable
data class WorldStateFactProposal(val name: String, val entityName: String, val value: String)

object WorldStateReducer {
    private val allowedKinds = setOf("npc", "location", "item", "other")

    fun applyProposal(state: WorldState, proposal: WorldStateProposal): WorldState? {
        if (proposal.baseRevision != state.revision || proposal.entities.size + proposal.facts.size > 8) return null
        val entities = state.entities.toMutableMap()
        val facts = state.facts.toMutableMap()
        val proposedIds = mutableSetOf<String>()

        for (entity in proposal.entities) {
            val id = canonicalEntityId(entity.name)
            if (id.isEmpty() || id.length > 64 || entity.kind !in allowedKinds ||
                entity.name.length !in 1..96 || entity.summary.length !in 1..240 || !proposedIds.add(id)
            ) return null
            entities.putIfAbsent(id, WorldEntity(entity.kind, entity.name, entity.summary))
        }

        for (fact in proposal.facts) {
            val entityId = canonicalEntityId(fact.entityName)
            if (entityId !in entities || fact.name.length !in 1..48 || fact.value.length !in 1..160) return null
            facts["${fact.name}:$entityId"] = fact.value
        }
        return WorldState(state.revision + 1, entities, facts)
    }

    fun canonicalEntityId(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(64)
        .trimEnd('-')

    fun snapshot(state: WorldState): String = if (state.entities.isEmpty() && state.facts.isEmpty()) "" else buildString {
        append("AUTHORITATIVE WORLD STATE — do not contradict:\n")
        state.entities.toSortedMap().entries.take(12).forEach { (id, entity) ->
            append("id=$id kind=${entity.kind} name=${entity.name} summary=${entity.summary}\n")
        }
        state.facts.toSortedMap().entries.take(12).forEach { append("${it.key} = ${it.value}\n") }
    }.trimEnd()
}
