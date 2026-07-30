package de.aidadventure.server.worldstate

import kotlinx.serialization.Serializable

@Serializable
data class WorldState(val location: String = "", val activeThread: String = "")

object WorldStateSnapshot {
    fun render(state: WorldState): String = buildList {
        state.location.takeIf { it.isNotBlank() }?.let { add("AUTHORITATIVE CURRENT LOCATION: $it") }
        state.activeThread.takeIf { it.isNotBlank() }?.let { add("OPEN STORY THREAD: $it") }
    }.joinToString("\n")
}
