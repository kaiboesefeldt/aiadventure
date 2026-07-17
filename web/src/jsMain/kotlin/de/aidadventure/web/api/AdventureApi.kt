package de.aidadventure.web.api

import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface AdventureApi {
    suspend fun prefill(request: PrefillRequest): PrefillAccepted
    suspend fun warmup(request: WarmupRequest): PrefillAccepted
    suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics
}

class AdventureApiClient(
    private val baseUrl: String = resolveAdventureApiBaseUrl(window.location.hostname, window.location.port),
) : AdventureApi {
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

    override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics {
        val response = requireBrowserSuccess(browserFetchTurn(request))
        val reader = response.body?.getReader() ?: throw AdventureApiException("The model response ended unexpectedly.")
        val textDecoder = js("new TextDecoder()")

        val decoder = SseFrameDecoder()
        while (true) {
            val result = reader.read().unsafeCast<Promise<dynamic>>().await()
            if (result.done.unsafeCast<Boolean>()) break
            processTurnStreamChunk(decoder, decodeText(textDecoder, result.value, stream = true), onToken)?.let { return it }
        }
        processTurnStreamChunk(decoder, decodeText(textDecoder, null, stream = false), onToken)?.let { return it }
        throw AdventureApiException("The model response ended unexpectedly.")
    }

    private suspend fun browserFetchTurn(request: TurnRequest): dynamic {
        return browserFetch("/api/turn", encodeTurnRequest(request))
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

internal fun encodeTurnRequest(request: TurnRequest): String =
    streamJson.encodeToString(TurnRequest.serializer(), request)

internal fun encodePrefillRequest(request: PrefillRequest): String =
    streamJson.encodeToString(PrefillRequest.serializer(), request)

internal fun encodeWarmupRequest(request: WarmupRequest): String =
    streamJson.encodeToString(WarmupRequest.serializer(), request)

internal fun resolveAdventureApiBaseUrl(hostname: String, port: String): String = when {
    port == "8088" && hostname in setOf("localhost", "127.0.0.1") -> "http://$hostname:8080"
    else -> ""
}

internal fun processTurnStreamChunk(
    decoder: SseFrameDecoder,
    chunk: String,
    onToken: (String) -> Unit,
): TurnMetrics? {
    for (frame in decoder.push(chunk)) {
        when (frame.event) {
            "token" -> onToken(streamJson.decodeFromString<StreamToken>(frame.data).text)
            "done" -> return streamJson.decodeFromString<StreamDone>(frame.data).metrics
            "error" -> throw AdventureApiException(streamJson.decodeFromString<StreamError>(frame.data).message)
        }
    }
    return null
}

private fun decodeText(decoder: dynamic, bytes: dynamic, stream: Boolean): String {
    val options = js("({})")
    options.stream = stream
    return decoder.decode(bytes, options).unsafeCast<String>()
}

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
    fun map(session: GameSession, scenario: Scenario, playerAction: String, sessionId: String = ""): TurnRequest = TurnRequest(
        scenarioMarkdown = scenario.markdown,
        history = session.messages.map { ChatEntry(it.role, it.content) },
        playerAction = playerAction,
        sessionId = sessionId,
    )
}

object PrefillRequestMapper {
    fun map(session: GameSession, scenario: Scenario, sessionId: String, revision: Long, action: String): PrefillRequest = PrefillRequest(
        sessionId = sessionId,
        revision = revision,
        scenarioMarkdown = scenario.markdown,
        history = session.messages.map { ChatEntry(it.role, it.content) },
        playerAction = action,
    )
}

object WarmupRequestMapper {
    fun map(session: GameSession, scenario: Scenario): WarmupRequest = WarmupRequest(
        sessionId = session.id,
        scenarioMarkdown = scenario.markdown,
        history = session.messages.map { ChatEntry(it.role, it.content) },
    )
}

@Serializable
data class ChatEntry(val role: String, val content: String)

@Serializable
data class TurnRequest(
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
    val sessionId: String,
)

@Serializable
data class PrefillRequest(
    val sessionId: String,
    val revision: Long,
    val scenarioMarkdown: String,
    val history: List<ChatEntry>,
    val playerAction: String,
)

@Serializable
data class WarmupRequest(
    val sessionId: String,
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

@Serializable
private data class StreamToken(val text: String)

@Serializable
private data class StreamDone(val metrics: TurnMetrics)

@Serializable
private data class StreamError(val code: String, val message: String)

@Serializable
private data class ModelsResponse(val models: List<String>)

@Serializable
private data class ApiError(val code: String, val message: String)

private val streamJson = Json { ignoreUnknownKeys = true }
