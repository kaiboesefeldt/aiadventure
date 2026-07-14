package de.aidadventure.server

import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.ollama.LangChain4jOllamaGateway
import de.aidadventure.server.ollama.OllamaGateway
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::adventureModule).start(wait = true)
}

fun Application.adventureModule(
    gateway: OllamaGateway = LangChain4jOllamaGateway(),
    clock: () -> Long = System::currentTimeMillis,
) {
    val modelCatalog = ModelCatalog(gateway, clock)
    install(ContentNegotiation) { json() }
    install(CORS) {
        allowHost("localhost:8088", schemes = listOf("http"))
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Request body is invalid."))
        }
    }
    install(Koin) {
        modules(module {
            single<OllamaGateway> { gateway }
            single { PromptFactory() }
        })
    }
    routing {
        get("/api/health") {
            call.respond(HealthResponse())
        }
        get("/api/models") {
            try {
                call.respond(ModelsResponse(modelCatalog.availableModels()))
            } catch (_: RuntimeException) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("ollama_unavailable", "The model service is unavailable."))
            }
        }
        post("/api/turn") {
            val request = call.receive<TurnRequest>()
            RequestValidator.validate(request)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, error)
                return@post
            }

            val models = try {
                modelCatalog.availableModels()
            } catch (_: RuntimeException) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("ollama_unavailable", "The model service is unavailable."))
                return@post
            }
            if (request.modelId !in models) {
                call.respond(HttpStatusCode.NotFound, ApiError("model_not_found", "The requested model is unavailable."))
                return@post
            }

            try {
                call.respond(gateway.play(request))
            } catch (_: RuntimeException) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("ollama_unavailable", "The model service is unavailable."))
            }
        }
    }
}

object RequestValidator {
    fun validate(request: TurnRequest): ApiError? = when {
        request.modelId.isBlank() || request.modelId.length > MAX_MODEL_ID_LENGTH -> invalid()
        request.scenarioMarkdown.isBlank() || request.scenarioMarkdown.length > MAX_SCENARIO_LENGTH -> invalid()
        request.playerAction.isBlank() || request.playerAction.length > MAX_TEXT_LENGTH -> invalid()
        request.history.size > MAX_HISTORY_ENTRIES -> invalid()
        request.history.any { !it.isValidHistoryEntry() } -> invalid()
        else -> null
    }

    private fun ChatEntry.isValidHistoryEntry(): Boolean =
        role in ALLOWED_HISTORY_ROLES && content.isNotBlank() && content.length <= MAX_TEXT_LENGTH

    private fun invalid() = ApiError("invalid_request", "Request contains invalid data.")

    private const val MAX_MODEL_ID_LENGTH = 256
    private const val MAX_SCENARIO_LENGTH = 200_000
    private const val MAX_TEXT_LENGTH = 20_000
    private const val MAX_HISTORY_ENTRIES = 200
    private val ALLOWED_HISTORY_ROLES = setOf("player", "user", "game-master", "assistant", "ai")
}

@Serializable
data class HealthResponse(val status: String = "ok")

@Serializable
data class ModelsResponse(val models: List<String>)

@Serializable
data class ApiError(val code: String, val message: String)

private class ModelCatalog(
    private val gateway: OllamaGateway,
    private val clock: () -> Long,
) {
    private var cachedModels: List<String>? = null
    private var cachedAtMillis: Long = 0

    @Synchronized
    fun availableModels(): List<String> {
        val now = clock()
        cachedModels?.takeIf { now - cachedAtMillis < TTL_MILLIS }?.let { return it }

        return gateway.availableModels().also {
            cachedModels = it
            cachedAtMillis = now
        }
    }

    private companion object {
        const val TTL_MILLIS = 30_000L
    }
}
