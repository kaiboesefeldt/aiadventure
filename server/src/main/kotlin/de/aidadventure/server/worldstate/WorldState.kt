package de.aidadventure.server.worldstate

import kotlinx.serialization.Serializable

@Serializable
data class WorldState(val location: String = "", val unresolvedSituation: String = "")

object WorldStateSnapshot {
    fun render(state: WorldState): String = buildList {
        state.location.takeIf { it.isNotBlank() }?.let { add("AUTHORITATIVE CURRENT LOCATION: $it") }
        state.unresolvedSituation.takeIf { it.isNotBlank() }?.let { add("UNRESOLVED STORY SITUATION: $it") }
    }.joinToString("\n")
}
