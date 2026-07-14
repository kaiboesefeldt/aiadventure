package de.aidadventure.web.api

import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import kotlinx.browser.window
import kotlinx.coroutines.await
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.js.Promise
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface AdventureApi {
    suspend fun models(): List<String>
    suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics
}

class AdventureApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: HttpClient = HttpClient(Js) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    },
) : AdventureApi {
    override suspend fun models(): List<String> {
        val response = client.get("$normalizedBaseUrl/api/models")
        return response.requireSuccess().body<ModelsResponse>().models
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
        val options = js("({})")
        options.method = "POST"
        options.headers = js("({ 'Content-Type': 'application/json' })")
        options.body = streamJson.encodeToString(request)
        return window.fetch("$normalizedBaseUrl/api/turn", options).await()
    }

    private suspend fun requireBrowserSuccess(response: dynamic): dynamic {
        if (response.ok.unsafeCast<Boolean>()) return response
        val message = runCatching {
            streamJson.decodeFromString<ApiError>(response.text().unsafeCast<Promise<String>>().await()).message
        }.getOrNull() ?: "The adventure service is unavailable."
        throw AdventureApiException(message)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.requireSuccess(): io.ktor.client.statement.HttpResponse {
        if (status.isSuccess()) return this
        val message = runCatching { body<ApiError>().message }.getOrNull() ?: "The adventure service is unavailable."
        throw AdventureApiException(message)
    }

    private val normalizedBaseUrl = baseUrl.removeSuffix("/")

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:8080"
    }
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
    fun map(session: GameSession, scenario: Scenario, playerAction: String): TurnRequest = TurnRequest(
        modelId = session.modelId,
        scenarioMarkdown = scenario.markdown,
        history = session.messages.map { ChatEntry(it.role, it.content) },
        playerAction = playerAction,
    )
}

@Serializable
data class ChatEntry(val role: String, val content: String)

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
