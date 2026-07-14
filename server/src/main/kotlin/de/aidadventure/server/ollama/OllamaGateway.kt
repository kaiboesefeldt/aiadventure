package de.aidadventure.server.ollama

import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnResponse
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaChatRequestParameters
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface OllamaGateway {
    fun availableModels(): List<String>

    fun play(request: TurnRequest): TurnResponse
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

    override fun play(request: TurnRequest): TurnResponse {
        val response = OllamaChatModel.builder()
            .baseUrl(normalizedBaseUrl)
            .modelName(request.modelId)
            .defaultRequestParameters(defaultRequestParameters(keepAliveSeconds))
            .build()
            .chat(promptFactory.build(request))

        return TurnResponse(
            message = ChatEntry(role = "game-master", content = response.aiMessage().text() ?: ""),
            metrics = TurnMetrics(
                totalDurationMs = null,
                promptTokens = response.tokenUsage()?.inputTokenCount(),
                cacheTokens = null,
            ),
        )
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
