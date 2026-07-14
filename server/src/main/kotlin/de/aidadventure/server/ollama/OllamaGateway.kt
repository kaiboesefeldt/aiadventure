package de.aidadventure.server.ollama

import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import dev.langchain4j.model.ollama.OllamaChatRequestParameters
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface OllamaGateway {
    fun availableModels(): List<String>

    fun stream(request: TurnRequest): Flow<TurnStreamEvent>
}

internal fun defaultRequestParameters(keepAliveSeconds: Int): OllamaChatRequestParameters =
    OllamaChatRequestParameters.builder()
        .keepAlive(keepAliveSeconds)
        .think(false)
        .build()

class LangChain4jOllamaGateway(
    private val promptFactory: PromptFactory = PromptFactory(),
    private val baseUrl: String = System.getenv("OLLAMA_BASE_URL") ?: DEFAULT_BASE_URL,
    keepAlive: String = System.getenv("OLLAMA_KEEP_ALIVE") ?: DEFAULT_KEEP_ALIVE,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : OllamaGateway {
    private val normalizedBaseUrl = baseUrl.removeSuffix("/")
    private val keepAliveSeconds = KeepAliveDuration.parseSeconds(keepAlive)

    override fun availableModels(): List<String> {
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create("$normalizedBaseUrl/api/tags")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "Ollama model discovery failed: HTTP ${response.statusCode()}" }
        return json.decodeFromString<OllamaTagsResponse>(response.body()).models.map { it.name }
    }

    override fun stream(request: TurnRequest): Flow<TurnStreamEvent> = callbackFlow {
        OllamaStreamingChatModel.builder()
            .baseUrl(normalizedBaseUrl)
            .modelName(request.modelId)
            .defaultRequestParameters(defaultRequestParameters(keepAliveSeconds))
            .build()
            .chat(promptFactory.build(request), object : StreamingChatResponseHandler {
                override fun onPartialResponse(partialResponse: String) {
                    sendOrClose(TurnStreamEvent.Token(partialResponse))
                }

                override fun onCompleteResponse(completeResponse: ChatResponse) {
                    sendOrClose(
                        TurnStreamEvent.Done(
                            TurnMetrics(
                                totalDurationMs = null,
                                promptTokens = completeResponse.tokenUsage()?.inputTokenCount(),
                                cacheTokens = null,
                            ),
                        ),
                    )
                    close()
                }

                override fun onError(error: Throwable) {
                    close(error)
                }
            })

        awaitClose()
    }

    @Serializable
    private data class OllamaTagsResponse(val models: List<OllamaTag>)

    @Serializable
    private data class OllamaTag(val name: String)

    private companion object {
        const val DEFAULT_BASE_URL = "http://localhost:11434"
        const val DEFAULT_KEEP_ALIVE = "10m"
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal fun <T> ProducerScope<T>.sendOrClose(event: T) {
    val result = trySendBlocking(event)
    if (result.isFailure) {
        close(result.exceptionOrNull() ?: IllegalStateException("Unable to deliver Ollama stream event."))
    }
}

object KeepAliveDuration {
    private val pattern = Regex("^(\\d+)([hms]?)$")

    fun parseSeconds(value: String): Int {
        val match = pattern.matchEntire(value)
            ?: throw IllegalArgumentException("OLLAMA_KEEP_ALIVE must be a positive duration such as 2h, 10m, 45s, or 30.")
        val multiplier = when (match.groupValues[2]) {
            "h" -> 3_600L
            "m" -> 60L
            else -> 1L
        }
        val seconds = match.groupValues[1].toLongOrNull()?.times(multiplier)
            ?: throw IllegalArgumentException("OLLAMA_KEEP_ALIVE is too large.")
        require(seconds in 1..Int.MAX_VALUE) { "OLLAMA_KEEP_ALIVE must be a positive duration no greater than ${Int.MAX_VALUE} seconds." }
        return seconds.toInt()
    }
}
