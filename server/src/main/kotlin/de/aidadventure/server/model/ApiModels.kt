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
    val direct: Boolean = false,
    val excludedConditions: List<List<FlagCondition>> = emptyList(),
)

/** One flag/value predicate or one exact current-location predicate. */
@Serializable
data class FlagCondition(val flag: String? = null, val value: Boolean? = null, val location: String? = null)

@Serializable
data class SignalCheckDefinition(
    val name: String,
    val description: String,
    val conditions: List<FlagCondition> = emptyList(),
    val emittedTexts: List<String> = emptyList(),
    val excludedConditions: List<List<FlagCondition>> = emptyList(),
    val eventCheck: Boolean = true,
)

@Serializable
data class FlagAssignment(val flag: String, val value: Boolean)

@Serializable
data class LocationTriggerAction(
    val setFlags: List<FlagAssignment> = emptyList(),
    val emitText: String? = null,
    val signals: List<String> = emptyList(),
)

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
    val signalChecks: List<SignalCheckDefinition> = emptyList(),
    val locationTriggers: Map<String, List<LocationTriggerAction>> = emptyMap(),
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
    val signalChecks: List<SignalCheckDefinition> = emptyList(),
    val locationTriggers: Map<String, List<LocationTriggerAction>> = emptyMap(),
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
    val signalChecks: List<SignalCheckDefinition> = emptyList(),
    val locationTriggers: Map<String, List<LocationTriggerAction>> = emptyMap(),
    val enteredLocations: List<String> = emptyList(),
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
    data class Fragment(val fragment: String, val emittedSignals: List<String> = emptyList(), val effectsReported: Boolean = false) : TurnStreamEvent
    data object GenerationReady : TurnStreamEvent
    data object InputReady : TurnStreamEvent
    data class Diagnostic(val value: StreamDiagnostic) : TurnStreamEvent
    data class Done(val metrics: TurnMetrics, val contextCompression: ContextCompression = ContextCompression()) : TurnStreamEvent
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StreamDiagnostic(
    val kind: String,
    val stage: String,
    val fields: Map<String, String> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val transcriptRole: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val metrics: DiagnosticMetrics? = null,
    val effects: List<StreamDiagnostic> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DiagnosticMetrics(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val promptTokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val contextTokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val predictedTokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cacheTokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val evaluatedTokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val promptDurationMs: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val predictedDurationMs: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val promptTokensPerSecond: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val predictedTokensPerSecond: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val totalDurationMs: Long? = null,
)

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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nextRuntimeContext: String = "",
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
