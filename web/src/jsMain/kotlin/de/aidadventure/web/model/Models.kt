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
    val contextCompression: ContextCompression = ContextCompression(),
    val flags: Map<String, Boolean> = emptyMap(),
)

@Serializable data class WorldState(val location: String = "", val narrativeFocus: String = "")

@Serializable
data class GameMessage(
    val role: String,
    val content: String,
    val createdAt: Long,
    val hidden: Boolean = false,
    val messageId: String? = null,
)

@Serializable
data class ContextCompression(
    val epoch: Long = 0,
    val active: ContextCheckpoint? = null,
    val pending: ContextCheckpoint? = null,
)

@Serializable
data class ContextCheckpoint(
    val recap: String,
    val coversThroughTurn: Int,
    val coversThroughEntry: Int,
    val sourceFingerprint: String,
    val stateRevision: Long,
    val modelId: String,
)

@Serializable
data class StreamDiagnostic(
    val kind: String,
    val stage: String,
    val fields: Map<String, String> = emptyMap(),
    val transcriptRole: String? = null,
    val metrics: DiagnosticMetrics? = null,
)

@Serializable
data class DiagnosticMetrics(
    val promptTokens: Int? = null,
    val contextTokens: Int? = null,
    val predictedTokens: Int? = null,
    val cacheTokens: Int? = null,
    val evaluatedTokens: Int? = null,
    val promptDurationMs: Double? = null,
    val predictedDurationMs: Double? = null,
    val promptTokensPerSecond: Double? = null,
    val predictedTokensPerSecond: Double? = null,
    val totalDurationMs: Long? = null,
)

@Serializable
data class DebugEvent(
    val id: String,
    val sessionId: String,
    val turnId: String,
    val sequence: Int,
    val createdAt: Long,
    val kind: String,
    val stage: String,
    val fields: Map<String, String> = emptyMap(),
    val metrics: DiagnosticMetrics? = null,
    val transcriptRole: String? = null,
    val turnOrder: Long = 0,
    val transcriptIndex: Int? = null,
    val transcriptMessageId: String? = null,
)
