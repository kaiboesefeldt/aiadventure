package de.aidadventure.web.api

import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface AdventureApi {
    suspend fun models(): List<String>
    suspend fun play(request: TurnRequest): TurnResponse
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

    override suspend fun play(request: TurnRequest): TurnResponse {
        val response = client.post("$normalizedBaseUrl/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.requireSuccess().body()
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

class AdventureApiException(message: String) : Exception(message)

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
data class TurnResponse(val message: ChatEntry, val metrics: TurnMetrics)

@Serializable
data class TurnMetrics(
    val totalDurationMs: Long?,
    val promptTokens: Int?,
    val cacheTokens: Int?,
)

@Serializable
private data class ModelsResponse(val models: List<String>)

@Serializable
private data class ApiError(val code: String, val message: String)
