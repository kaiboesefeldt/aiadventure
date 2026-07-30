package de.aidadventure.server.worldstate

import kotlinx.serialization.Serializable

@Serializable
data class WorldState(val location: String = "")

object WorldStateSnapshot {
    fun render(state: WorldState): String = state.location
        .takeIf { it.isNotBlank() }
        ?.let { "AUTHORITATIVE CURRENT LOCATION: $it" }
        .orEmpty()
}
