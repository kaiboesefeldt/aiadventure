package de.aidadventure.server.worldstate

import kotlinx.serialization.Serializable

@Serializable
data class WorldState(val location: String = "", val narrativeFocus: String = "")

object WorldStateSnapshot {
    fun render(state: WorldState): String = buildList {
        state.location.takeIf { it.isNotBlank() }?.let { add("AUTHORITATIVE CURRENT LOCATION: $it") }
        state.narrativeFocus.takeIf { it.isNotBlank() }?.let { add("NARRATIVE FOCUS: $it") }
    }.joinToString("\n")
}
