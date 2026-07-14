package de.aidadventure.server.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatEntry(
    val role: String,
    val content: String,
)

@Serializable
data class TurnRequest(
    val modelId: String,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
)

@Serializable
data class TurnMetrics(
    val totalDurationMs: Long?,
    val promptTokens: Int?,
    val cacheTokens: Int?,
)

@Serializable
data class TurnResponse(
    val message: ChatEntry,
    val metrics: TurnMetrics,
)
