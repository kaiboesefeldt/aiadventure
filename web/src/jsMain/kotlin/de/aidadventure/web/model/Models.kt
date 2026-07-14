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
    val modelId: String,
    val messages: List<GameMessage>,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class GameMessage(
    val role: String,
    val content: String,
    val createdAt: Long,
)
