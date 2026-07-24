package de.aidadventure.server.worldstate

import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorldState(val revision: Long = 0, val entities: Map<String, WorldEntity> = emptyMap(), val facts: Map<String, String> = emptyMap())

@Serializable
data class WorldEntity(val kind: String, val name: String, val summary: String)

@Serializable
data class WorldStatePatch(val baseRevision: Long, val operations: List<WorldStateOperation>)

@Serializable
sealed class WorldStateOperation {
    @Serializable @SerialName("createEntity")
    data class CreateEntity(val id: String, val kind: String, val name: String, val summary: String) : WorldStateOperation()
    @Serializable @SerialName("setFact")
    data class SetFact(val name: String, val entityId: String, val value: String) : WorldStateOperation()
}

object WorldStateReducer {
    private val allowedKinds = setOf("npc", "location", "item", "other")

    fun apply(state: WorldState, patch: WorldStatePatch): WorldState? {
        if (patch.baseRevision != state.revision || patch.operations.size > 8) return null
        val entities = state.entities.toMutableMap(); val facts = state.facts.toMutableMap()
        for (operation in patch.operations) when (operation) {
            is WorldStateOperation.CreateEntity -> {
                if (operation.id !in entities && operation.id.matches(Regex("[a-z][a-z0-9-]{0,63}")) &&
                    operation.id == canonicalEntityId(operation.name) && operation.kind in allowedKinds &&
                    entities.values.none { canonicalEntityId(it.name) == operation.id } &&
                    operation.name.length in 1..96 && operation.summary.length in 1..240) {
                    entities[operation.id] = WorldEntity(operation.kind, operation.name, operation.summary)
                } else return null
            }
            is WorldStateOperation.SetFact -> {
                if (operation.entityId !in entities || operation.name.length !in 1..48 || operation.value.length !in 1..160) return null
                facts["${operation.name}:${operation.entityId}"] = operation.value
            }
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
