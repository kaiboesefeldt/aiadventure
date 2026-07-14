package de.aidadventure.server

import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.ModelProvider
import de.aidadventure.server.model.StreamDone
import de.aidadventure.server.model.StreamError
import de.aidadventure.server.model.StreamToken
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.llamacpp.LlamaCppGateway
import de.aidadventure.server.llamacpp.LlamaCppPort
import de.aidadventure.server.ollama.LangChain4jOllamaGateway
import de.aidadventure.server.ollama.OllamaGateway
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondResource
import io.ktor.server.response.respondTextWriter
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.io.Writer
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import java.util.UUID

fun main() {
    val config = ServerRuntimeConfig.from(System.getenv())
    embeddedServer(Netty, port = config.port, host = config.host, module = Application::adventureModule).start(wait = true)
}

data class ServerRuntimeConfig(val host: String, val port: Int) {
    companion object {
        fun from(environment: Map<String, String>): ServerRuntimeConfig {
            val exposeLan = environment["ADVENTURE_EXPOSE_LAN"] ?: "false"
            require(exposeLan == "true" || exposeLan == "false") {
                "ADVENTURE_EXPOSE_LAN must be either true or false."
            }
            return ServerRuntimeConfig(
                host = if (exposeLan == "true") "0.0.0.0" else "127.0.0.1",
                port = environment["PORT"]?.toIntOrNull() ?: 8080,
            )
        }
    }
}

fun Application.adventureModule(
    gateway: OllamaGateway = LangChain4jOllamaGateway(),
    llamaCppGateway: LlamaCppGateway = LlamaCppGateway(),
    clock: () -> Long = System::currentTimeMillis,
) {
    val logger = LoggerFactory.getLogger("de.aidadventure.server.AdventureApi")
    val modelCatalog = ModelCatalog(gateway, clock)
    install(ContentNegotiation) { json() }
    install(CORS) {
        allowHost("localhost:8088", schemes = listOf("http"))
        allowHost("127.0.0.1:8088", schemes = listOf("http"))
        allowHeader(HttpHeaders.ContentType)
    }
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Request body is invalid."))
        }
    }
    install(Koin) {
        modules(module {
            single<OllamaGateway> { gateway }
            single<LlamaCppGateway> { llamaCppGateway }
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
            } catch (failure: RuntimeException) {
                logger.warn("requestId={} operation=models result=ollama_unavailable cause={}", call.callId, failure.javaClass.simpleName)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("ollama_unavailable", "The model service is unavailable."))
            }
        }
        get("/api/llama/models") {
            val port = try {
                LlamaCppPort.parse(call.request.queryParameters["port"] ?: "")
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, invalidRequestError())
                return@get
            }

            try {
                call.respond(ModelsResponse(llamaCppGateway.availableModels(port)))
            } catch (failure: RuntimeException) {
                logger.warn("requestId={} operation=llama_models result=llama_cpp_unavailable cause={}", call.callId, failure.javaClass.simpleName)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("llama_cpp_unavailable", "The model service is unavailable."))
            } catch (failure: IOException) {
                logger.warn("requestId={} operation=llama_models result=llama_cpp_unavailable cause={}", call.callId, failure.javaClass.simpleName)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("llama_cpp_unavailable", "The model service is unavailable."))
            }
        }
        post("/api/turn") {
            val request = call.receive<TurnRequest>()
            RequestValidator.validate(request)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, error)
                return@post
            }

            val selectedGateway = when (request.provider) {
                ModelProvider.OLLAMA -> try {
                    TurnGateway(
                        models = modelCatalog.availableModels(),
                        stream = { gateway.stream(request) },
                        unavailableCode = "ollama_unavailable",
                    )
                } catch (failure: RuntimeException) {
                    logger.warn("requestId={} operation=model_validation result=ollama_unavailable cause={}", call.callId, failure.javaClass.simpleName)
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("ollama_unavailable", "The model service is unavailable."))
                    return@post
                }
                ModelProvider.LLAMA_CPP -> {
                    val port = try {
                        LlamaCppPort.parse(request.llamaCppPort?.toString() ?: "")
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, invalidRequestError())
                        return@post
                    }

                    try {
                        TurnGateway(
                            models = llamaCppGateway.availableModels(port),
                            stream = { llamaCppGateway.stream(request) },
                            unavailableCode = "llama_cpp_unavailable",
                        )
                    } catch (failure: RuntimeException) {
                        logger.warn("requestId={} operation=model_validation result=llama_cpp_unavailable cause={}", call.callId, failure.javaClass.simpleName)
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("llama_cpp_unavailable", "The model service is unavailable."))
                        return@post
                    } catch (failure: IOException) {
                        logger.warn("requestId={} operation=model_validation result=llama_cpp_unavailable cause={}", call.callId, failure.javaClass.simpleName)
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("llama_cpp_unavailable", "The model service is unavailable."))
                        return@post
                    }
                }
            }

            if (request.modelId !in selectedGateway.models) {
                call.respond(HttpStatusCode.NotFound, ApiError("model_not_found", "The requested model is unavailable."))
                return@post
            }

            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    sseComment("connected")
                    selectedGateway.stream().collect { event ->
                        when (event) {
                            is TurnStreamEvent.Token -> sse("token", eventJson.encodeToString(StreamToken(event.text)))
                            is TurnStreamEvent.Done -> sse("done", eventJson.encodeToString(StreamDone(event.metrics)))
                        }
                    }
                } catch (_: ClosedWriteChannelException) {
                    logger.debug("requestId={} operation=turn result=client_disconnected", call.callId)
                } catch (failure: Exception) {
                    logger.warn("requestId={} operation=turn result={} cause={}", call.callId, selectedGateway.unavailableCode, failure.javaClass.simpleName)
                    try {
                        sse("error", eventJson.encodeToString(StreamError(selectedGateway.unavailableCode, "The model service is unavailable.")))
                    } catch (_: ClosedWriteChannelException) {
                        logger.debug("requestId={} operation=turn result=client_disconnected", call.callId)
                    }
                }
            }
        }
        get("/") {
            call.respondResource("ui/index.html")
        }
        staticResources("/", "ui", index = null)
    }
}

private data class TurnGateway(
    val models: List<String>,
    val stream: () -> Flow<TurnStreamEvent>,
    val unavailableCode: String,
)

private fun invalidRequestError(): ApiError = ApiError("invalid_request", "Request contains invalid data.")

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

private val eventJson = Json { encodeDefaults = true }

private fun Writer.sse(event: String, data: String) {
    write("event: $event\ndata: $data\n\n")
    flush()
}

private fun Writer.sseComment(comment: String) {
    write(": $comment\n\n")
    flush()
}

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
