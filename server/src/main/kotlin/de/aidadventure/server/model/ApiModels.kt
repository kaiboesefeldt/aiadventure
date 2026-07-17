package de.aidadventure.server.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatEntry(
    val role: String,
    val content: String,
)

@Serializable
data class TurnRequest(
    val sessionId: String,
    val modelId: String,
    val llamaCppPort: Int,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
)

@Serializable
data class PrefillRequest(
    val sessionId: String,
    val revision: Long,
    val modelId: String,
    val llamaCppPort: Int,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
)

@Serializable
data class WarmupRequest(
    val sessionId: String,
    val modelId: String,
    val llamaCppPort: Int,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
)

@Serializable
enum class PrefillDisposition { STARTED, QUEUED, STALE, TURN_IN_PROGRESS }

@Serializable
data class PrefillAccepted(val disposition: PrefillDisposition)

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
