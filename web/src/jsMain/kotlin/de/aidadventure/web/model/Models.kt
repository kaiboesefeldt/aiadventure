package de.aidadventure.web.model

import kotlinx.serialization.Serializable

@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val markdown: String,
    val importedAt: Long,
)

@Serializable
data class GameSession(
    val id: String,
    val scenarioId: String,
    val title: String,
    val modelId: String = "configured-llama-cpp",
    val messages: List<GameMessage>,
    val createdAt: Long,
    val updatedAt: Long,
    val llamaCppPort: Int = 8081,
    val worldState: WorldState = WorldState(),
    val introductionPending: Boolean = false,
)

@Serializable data class WorldState(val location: String = "", val narrativeFocus: String = "")

@Serializable
data class GameMessage(
    val role: String,
    val content: String,
    val createdAt: Long,
    val hidden: Boolean = false,
)
