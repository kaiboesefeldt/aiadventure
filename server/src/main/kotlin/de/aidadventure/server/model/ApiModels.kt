package de.aidadventure.server.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import de.aidadventure.server.worldstate.WorldState

@Serializable
data class ChatEntry(
    val role: String,
    val content: String,
)

@Serializable
data class RuntimeFragmentDefinition(
    val matchText: String,
    val fragment: String,
    val conditions: List<FlagCondition> = emptyList(),
    val emittedSignals: List<String> = emptyList(),
)

@Serializable
data class FlagCondition(val flag: String, val value: Boolean)

@Serializable
data class FlagAssignment(val flag: String, val value: Boolean)

data class FragmentRelevanceRequest(
    val sessionId: String,
    val modelId: String = "",
    val llamaCppPort: Int = 8081,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val candidateKey: String,
    val worldState: WorldState = WorldState(),
    val contextCompression: ContextCompression = ContextCompression(),
    val locations: Map<String, String> = emptyMap(),
)

@Serializable
data class TurnRequest(
    val sessionId: String,
    @Transient val modelId: String = "",
    @Transient val llamaCppPort: Int = 8081,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
    val runtimeFragments: List<RuntimeFragmentDefinition> = emptyList(),
    val worldState: WorldState = WorldState(),
    val contextCompression: ContextCompression = ContextCompression(),
    val locations: Map<String, String> = emptyMap(),
    val initialSituation: String = "",
    val flags: Map<String, Boolean> = emptyMap(),
    val signals: Map<String, List<FlagAssignment>> = emptyMap(),
)

@Serializable
data class PrefillRequest(
    val sessionId: String,
    val revision: Long,
    @Transient val modelId: String = "",
    @Transient val llamaCppPort: Int = 8081,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
    val runtimeFragments: List<RuntimeFragmentDefinition> = emptyList(),
    val worldState: WorldState = WorldState(),
    val contextCompression: ContextCompression = ContextCompression(),
    val locations: Map<String, String> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val signals: Map<String, List<FlagAssignment>> = emptyMap(),
)

@Serializable
data class WarmupRequest(
    val sessionId: String,
    @Transient val modelId: String = "",
    @Transient val llamaCppPort: Int = 8081,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val runtimeFragments: List<RuntimeFragmentDefinition> = emptyList(),
    val worldState: WorldState = WorldState(),
    val contextCompression: ContextCompression = ContextCompression(),
    val locations: Map<String, String> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val signals: Map<String, List<FlagAssignment>> = emptyMap(),
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
    data class Fragment(val fragment: String, val emittedSignals: List<String> = emptyList()) : TurnStreamEvent
    data object GenerationReady : TurnStreamEvent
    data object InputReady : TurnStreamEvent
    data class Done(val metrics: TurnMetrics, val contextCompression: ContextCompression = ContextCompression()) : TurnStreamEvent
}

@Serializable
data class StreamToken(val text: String)

@Serializable
data class StreamFragment(val fragment: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StreamDone(
    val metrics: TurnMetrics,
    val worldState: WorldState = WorldState(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val contextCompression: ContextCompression = ContextCompression(),
    val flags: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class StreamError(val code: String, val message: String)

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
