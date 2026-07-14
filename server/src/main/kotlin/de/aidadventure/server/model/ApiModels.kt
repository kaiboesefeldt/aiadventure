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

sealed interface TurnStreamEvent {
    data class Token(val text: String) : TurnStreamEvent
    data class Done(val metrics: TurnMetrics) : TurnStreamEvent
}

@Serializable
data class StreamToken(val text: String)

@Serializable
data class StreamDone(val metrics: TurnMetrics)

@Serializable
data class StreamError(val code: String, val message: String)
