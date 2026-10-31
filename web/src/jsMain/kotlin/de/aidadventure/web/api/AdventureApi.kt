package de.aidadventure.web.api

import de.aidadventure.web.model.ContextCompression
import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.model.StreamDiagnostic
import de.aidadventure.web.model.WorldState
import de.aidadventure.web.persistence.ScenarioFrontmatter
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface AdventureApi {
    suspend fun prefill(request: PrefillRequest): PrefillAccepted
    suspend fun warmup(request: WarmupRequest): PrefillAccepted
    suspend fun releaseSession(sessionId: String) = Unit
    suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion
    suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion =
        play(request, onInputReady, onFragment, onToken)
    suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion =
        play(request, onGenerationReady, onInputReady, onFragment, onToken)
    suspend fun awaitBackgroundDiagnostic(sessionId: String, operationId: String): StreamDiagnostic? = null
    fun stopActiveTurn() = Unit
}

class AdventureApiClient(
    private val baseUrl: String = "",
) : AdventureApi {
    private var activeTurnController: dynamic = null
    private var activeTurnReader: dynamic = null
    private var activeTurnStopped = false

    override fun stopActiveTurn() {
        if (activeTurnController == null && activeTurnReader == null) return
        activeTurnStopped = true
        runCatching { activeTurnReader?.cancel() }
        runCatching { activeTurnController?.abort() }
    }
    override suspend fun prefill(request: PrefillRequest): PrefillAccepted {
        val response = browserFetch("/api/prefill", encodePrefillRequest(request))
        if (response.status.unsafeCast<Int>() != 202) throw browserFailure(response)
        return streamJson.decodeFromString(response.text().unsafeCast<Promise<String>>().await())
    }

    override suspend fun warmup(request: WarmupRequest): PrefillAccepted {
        val response = browserFetch("/api/warmup", encodeWarmupRequest(request))
        if (response.status.unsafeCast<Int>() != 202) throw browserFailure(response)
        return streamJson.decodeFromString(response.text().unsafeCast<Promise<String>>().await())
    }

    override suspend fun releaseSession(sessionId: String) {
        val response = browserFetch("/api/session/release", encodeSessionReleaseRequest(SessionReleaseRequest(sessionId)))
        requireBrowserSuccess(response)
    }

    override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
        return play(request, {}, onInputReady, onFragment, onToken)
    }

    override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
        return play(request, onGenerationReady, onInputReady, onFragment, onToken, {})
    }

    override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
        activeTurnStopped = false
        val controller = js("new AbortController()")
        activeTurnController = controller
        try {
        val response = requireBrowserSuccess(browserFetchTurn(request, controller))
        val reader = response.body?.getReader() ?: throw AdventureApiException("The model response ended unexpectedly.")
        activeTurnReader = reader
        val textDecoder = js("new TextDecoder()")

        val decoder = SseFrameDecoder()
        while (true) {
            val result = try { reader.read().unsafeCast<Promise<dynamic>>().await() } catch (failure: Throwable) {
                if (activeTurnStopped) throw TurnStreamStoppedException()
                throw failure
            }
            if (activeTurnStopped) throw TurnStreamStoppedException()
            if (result.done.unsafeCast<Boolean>()) break
            processTurnStreamChunk(decoder, decodeText(textDecoder, result.value, stream = true), onInputReady, onFragment, onToken, onGenerationReady, { activeTurnStopped }, onDiagnostic)?.let { return it }
        }
        processTurnStreamChunk(decoder, decodeText(textDecoder, null, stream = false), onInputReady, onFragment, onToken, onGenerationReady, { activeTurnStopped }, onDiagnostic)?.let { return it }
        throw AdventureApiException("The model response ended unexpectedly.")
        } finally {
            activeTurnReader = null
            activeTurnController = null
        }
    }

    override suspend fun awaitBackgroundDiagnostic(sessionId: String, operationId: String): StreamDiagnostic? {
        val path = "/api/turn/background?sessionId=${encodeQueryComponent(sessionId)}&operationId=${encodeQueryComponent(operationId)}"
        val response = window.fetch("$normalizedBaseUrl$path").await().asDynamic()
        if (response.status.unsafeCast<Int>() == 404) return null
        requireBrowserSuccess(response)
        val reader = response.body?.getReader() ?: throw AdventureApiException("The background result ended unexpectedly.")
        val textDecoder = js("new TextDecoder()")
        val decoder = SseFrameDecoder()
        var diagnostic: StreamDiagnostic? = null
        fun consume(chunk: String) {
            decoder.push(chunk).forEach { frame ->
                if (frame.event == "diagnostic") {
                    if (diagnostic != null) throw AdventureApiException("The background result contained multiple diagnostics.")
                    diagnostic = streamJson.decodeFromString<StreamDiagnostic>(frame.data)
                }
            }
        }
        while (true) {
            val result = reader.read().unsafeCast<Promise<dynamic>>().await()
            if (result.done.unsafeCast<Boolean>()) break
            consume(decodeText(textDecoder, result.value, stream = true))
        }
        consume(decodeText(textDecoder, null, stream = false))
        return diagnostic ?: throw AdventureApiException("The background result ended unexpectedly.")
    }

    private suspend fun browserFetchTurn(request: TurnRequest, controller: dynamic): dynamic {
        val options = js("({})")
        options.method = "POST"
        options.headers = js("({ 'Content-Type': 'application/json' })")
        options.body = encodeTurnRequest(request)
        options.signal = controller.signal
        return try { window.fetch("$normalizedBaseUrl/api/turn", options).await() } catch (failure: Throwable) {
            if (activeTurnStopped) throw TurnStreamStoppedException()
            throw failure
        }
    }

    private suspend fun browserFetch(path: String, body: String): dynamic {
        val options = js("({})")
        options.method = "POST"
        options.headers = js("({ 'Content-Type': 'application/json' })")
        options.body = body
        return window.fetch("$normalizedBaseUrl$path", options).await()
    }

    private suspend fun requireBrowserSuccess(response: dynamic): dynamic {
        if (response.ok.unsafeCast<Boolean>()) return response
        throw browserFailure(response)
    }

    private suspend fun browserFailure(response: dynamic): AdventureApiException {
        val message = runCatching {
            streamJson.decodeFromString<ApiError>(response.text().unsafeCast<Promise<String>>().await()).message
        }.getOrNull() ?: "The adventure service is unavailable."
        return AdventureApiException(message)
    }

    private val normalizedBaseUrl = baseUrl.removeSuffix("/")

}

class TurnStreamStoppedException : Exception("Turn stream stopped by player input.")

internal fun encodeTurnRequest(request: TurnRequest): String =
    streamJson.encodeToString(TurnRequest.serializer(), request)

internal fun encodePrefillRequest(request: PrefillRequest): String =
    streamJson.encodeToString(PrefillRequest.serializer(), request)

internal fun encodeWarmupRequest(request: WarmupRequest): String =
    streamJson.encodeToString(WarmupRequest.serializer(), request)

internal fun encodeSessionReleaseRequest(request: SessionReleaseRequest): String =
    streamJson.encodeToString(SessionReleaseRequest.serializer(), request)

@Serializable
data class SessionReleaseRequest(val sessionId: String)

internal fun processTurnStreamChunk(
    decoder: SseFrameDecoder,
    chunk: String,
    onInputReady: () -> Unit = {},
    onFragment: (String) -> Unit,
    onToken: (String) -> Unit,
    onGenerationReady: () -> Unit = {},
    shouldStop: () -> Boolean = { false },
    onDiagnostic: (StreamDiagnostic) -> Unit = {},
): TurnCompletion? {
    for (frame in decoder.push(chunk)) {
        when (frame.event) {
            "generation_ready" -> {
                onGenerationReady()
                if (shouldStop()) throw TurnStreamStoppedException()
            }
            "input_ready" -> onInputReady()
            "fragment" -> onFragment(streamJson.decodeFromString<StreamFragment>(frame.data).fragment)
            "token" -> onToken(streamJson.decodeFromString<StreamToken>(frame.data).text)
            "diagnostic" -> onDiagnostic(streamJson.decodeFromString<StreamDiagnostic>(frame.data))
            "done" -> streamJson.decodeFromString<StreamDone>(frame.data).let { return TurnCompletion(it.metrics, it.worldState, it.contextCompression, it.flags, it.nextRuntimeContext) }
            "error" -> throw AdventureApiException(streamJson.decodeFromString<StreamError>(frame.data).message)
        }
    }
    return null
}

private fun decodeText(decoder: dynamic, bytes: dynamic, stream: Boolean): String {
    if (bytes == null) return decoder.decode().unsafeCast<String>()
    val options = js("({})")
    options.stream = stream
    return decoder.decode(bytes, options).unsafeCast<String>()
}

private fun encodeQueryComponent(value: String): String = js("encodeURIComponent")(value).unsafeCast<String>()

class AdventureApiException(message: String) : Exception(message)

data class SseFrame(val event: String, val data: String)

class SseFrameDecoder {
    private var buffer = ""

    fun push(chunk: String): List<SseFrame> {
        buffer = (buffer + chunk).replace("\r\n", "\n")
        val frames = mutableListOf<SseFrame>()

        while (true) {
            val frameEnd = buffer.indexOf("\n\n")
            if (frameEnd < 0) return frames

            val frameText = buffer.substring(0, frameEnd)
            buffer = buffer.substring(frameEnd + 2)

            val event = frameText.lineSequence()
                .firstOrNull { it.startsWith("event:") }
                ?.removePrefix("event:")
                ?.trimStart()
            val data = frameText.lineSequence()
                .firstOrNull { it.startsWith("data:") }
                ?.removePrefix("data:")
                ?.trimStart()
            if (event != null && data != null) frames += SseFrame(event, data)
        }
    }
}

object TurnRequestMapper {
    fun map(
        session: GameSession,
        scenario: Scenario,
        playerAction: String,
        sessionId: String = "",
        includeInitialSituation: Boolean = false,
    ): TurnRequest {
        val parsed = ScenarioFrontmatter.parse(scenario.markdown)
        return TurnRequest(
            scenarioMarkdown = parsed.promptMarkdown,
            history = session.messages.map { ChatEntry(it.role, it.content) },
            playerAction = playerAction,
            sessionId = sessionId,
            runtimeFragments = parsed.runtimeFragments.map { it.toApi() },
            worldState = session.worldState,
            contextCompression = session.contextCompression,
            locations = parsed.locations,
            initialSituation = if (includeInitialSituation) parsed.initialSituationSetting else "",
            flags = parsed.initialFlags + session.flags,
            signals = parsed.signals.mapValues { (_, assignments) -> assignments.map { FlagAssignment(it.flag, it.value) } },
            signalChecks = parsed.signalChecks.map { it.toApi() },
        )
    }
}

object PrefillRequestMapper {
    fun map(session: GameSession, scenario: Scenario, sessionId: String, revision: Long, action: String): PrefillRequest {
        val parsed = ScenarioFrontmatter.parse(scenario.markdown)
        return PrefillRequest(
            sessionId = sessionId,
            revision = revision,
            scenarioMarkdown = parsed.promptMarkdown,
            history = session.messages.map { ChatEntry(it.role, it.content) },
            playerAction = action,
            runtimeFragments = parsed.runtimeFragments.map { it.toApi() },
            worldState = session.worldState,
            contextCompression = session.contextCompression,
            locations = parsed.locations,
            flags = parsed.initialFlags + session.flags,
            signals = parsed.signals.mapValues { (_, assignments) -> assignments.map { FlagAssignment(it.flag, it.value) } },
            signalChecks = parsed.signalChecks.map { it.toApi() },
        )
    }
}

object WarmupRequestMapper {
    fun map(session: GameSession, scenario: Scenario): WarmupRequest {
        val parsed = ScenarioFrontmatter.parse(scenario.markdown)
        return WarmupRequest(
            sessionId = session.id,
            scenarioMarkdown = parsed.promptMarkdown,
            history = session.messages.map { ChatEntry(it.role, it.content) },
            runtimeFragments = parsed.runtimeFragments.map { it.toApi() },
            worldState = session.worldState,
            contextCompression = session.contextCompression,
            locations = parsed.locations,
            flags = parsed.initialFlags + session.flags,
            signals = parsed.signals.mapValues { (_, assignments) -> assignments.map { FlagAssignment(it.flag, it.value) } },
            signalChecks = parsed.signalChecks.map { it.toApi() },
        )
    }
}

private fun de.aidadventure.web.persistence.RuntimeFragmentDefinition.toApi() = RuntimeFragmentDefinition(
    matchText = matchText,
    fragment = fragment,
    conditions = conditions.map { FlagCondition(it.flag, it.value, it.location) },
    emittedSignals = emittedSignals,
    direct = direct,
    excludedConditions = excludedConditions.map { group -> group.map { FlagCondition(it.flag, it.value, it.location) } },
)

private fun de.aidadventure.web.persistence.SignalCheckDefinition.toApi() = SignalCheckDefinition(
    name = name,
    description = description,
    conditions = conditions.map { FlagCondition(it.flag, it.value, it.location) },
    emittedTexts = emittedTexts,
    excludedConditions = excludedConditions.map { group -> group.map { FlagCondition(it.flag, it.value, it.location) } },
    eventCheck = eventCheck,
)

@Serializable
data class ChatEntry(val role: String, val content: String)

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
data class TurnRequest(
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
    val sessionId: String,
    val runtimeFragments: List<RuntimeFragmentDefinition> = emptyList(),
    val worldState: WorldState = WorldState(),
    val contextCompression: ContextCompression = ContextCompression(),
    val locations: Map<String, String> = emptyMap(),
    val initialSituation: String = "",
    val flags: Map<String, Boolean> = emptyMap(),
    val signals: Map<String, List<FlagAssignment>> = emptyMap(),
    val signalChecks: List<SignalCheckDefinition> = emptyList(),
)

@Serializable
data class PrefillRequest(
    val sessionId: String,
    val revision: Long,
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
)

@Serializable
data class WarmupRequest(
    val sessionId: String,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val runtimeFragments: List<RuntimeFragmentDefinition> = emptyList(),
    val worldState: WorldState = WorldState(),
    val contextCompression: ContextCompression = ContextCompression(),
    val locations: Map<String, String> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val signals: Map<String, List<FlagAssignment>> = emptyMap(),
    val signalChecks: List<SignalCheckDefinition> = emptyList(),
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

@Serializable
private data class StreamToken(val text: String)

@Serializable
private data class StreamFragment(val fragment: String)

@Serializable
private data class StreamDone(
    val metrics: TurnMetrics,
    val worldState: WorldState = WorldState(),
    val contextCompression: ContextCompression = ContextCompression(),
    val flags: Map<String, Boolean> = emptyMap(),
    val nextRuntimeContext: String = "",
)
data class TurnCompletion(
    val metrics: TurnMetrics,
    val worldState: WorldState,
    val contextCompression: ContextCompression = ContextCompression(),
    val flags: Map<String, Boolean> = emptyMap(),
    val nextRuntimeContext: String = "",
)

@Serializable
private data class StreamError(val code: String, val message: String)

@Serializable
private data class ModelsResponse(val models: List<String>)

@Serializable
private data class ApiError(val code: String, val message: String)

private val streamJson = Json { ignoreUnknownKeys = true }
