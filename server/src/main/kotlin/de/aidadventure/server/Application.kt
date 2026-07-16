package de.aidadventure.server

import de.aidadventure.server.llamacpp.LlamaCppGateway
import de.aidadventure.server.llamacpp.LlamaCppPort
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.PrefillAccepted
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.StreamDone
import de.aidadventure.server.model.StreamError
import de.aidadventure.server.model.StreamToken
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.prefill.CoordinatorKey
import de.aidadventure.server.prefill.ExpiringPrefillRegistry
import de.aidadventure.server.prefill.PrefillRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondResource
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ClosedWriteChannelException
import java.io.IOException
import java.io.Writer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
    llamaCppGateway: LlamaCppGateway = LlamaCppGateway(),
    prefillRegistry: PrefillRegistry = ExpiringPrefillRegistry(),
    clock: () -> Long = System::currentTimeMillis,
) {
    val logger = LoggerFactory.getLogger("de.aidadventure.server.AdventureApi")
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
    routing {
        get("/api/health") { call.respond(HealthResponse()) }
        get("/api/models") {
            val port = call.validLlamaCppPortOrRespond() ?: return@get
            try {
                call.respond(ModelsResponse(llamaCppGateway.availableModels(port)))
            } catch (failure: RuntimeException) {
                call.respondLlamaUnavailable(logger, "models", failure)
            } catch (failure: IOException) {
                call.respondLlamaUnavailable(logger, "models", failure)
            }
        }
        post("/api/prefill") {
            val request = call.receive<PrefillRequest>()
            RequestValidator.validate(request)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, error)
                return@post
            }
            val key = CoordinatorKey(request.sessionId, request.modelId, request.llamaCppPort)
            try {
                prefillRegistry.removeIdle(clock())
                call.respond(HttpStatusCode.Accepted, PrefillAccepted(prefillRegistry.coordinatorFor(key).submit(request)))
            } catch (failure: IOException) {
                call.respondLlamaUnavailable(logger, "prefill", failure)
            } catch (failure: RuntimeException) {
                if (failure is CancellationException) throw failure
                call.respondLlamaUnavailable(logger, "prefill", failure)
            }
        }
        post("/api/turn") {
            val request = call.receive<TurnRequest>()
            RequestValidator.validate(request)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, error)
                return@post
            }
            try {
                if (request.modelId !in llamaCppGateway.availableModels(request.llamaCppPort)) {
                    call.respond(HttpStatusCode.NotFound, ApiError("model_not_found", "The requested model is unavailable."))
                    return@post
                }
            } catch (failure: RuntimeException) {
                call.respondLlamaUnavailable(logger, "model_validation", failure)
                return@post
            } catch (failure: IOException) {
                call.respondLlamaUnavailable(logger, "model_validation", failure)
                return@post
            }

            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    sseComment("connected")
                    prefillRegistry.removeIdle(clock())
                    val stream = prefillRegistry
                        .coordinatorFor(CoordinatorKey(request.sessionId, request.modelId, request.llamaCppPort))
                        .beginTurn(request)
                    stream.collect { event ->
                        when (event) {
                            is TurnStreamEvent.Token -> sse("token", eventJson.encodeToString(StreamToken(event.text)))
                            is TurnStreamEvent.Done -> sse("done", eventJson.encodeToString(StreamDone(event.metrics)))
                        }
                    }
                } catch (_: ClosedWriteChannelException) {
                    logger.debug("requestId={} operation=turn result=client_disconnected", call.callId)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    logger.warn("requestId={} operation=turn result=llama_cpp_unavailable cause={}", call.callId, failure.javaClass.simpleName)
                    try {
                        sse("error", eventJson.encodeToString(StreamError("llama_cpp_unavailable", "The model service is unavailable.")))
                    } catch (_: ClosedWriteChannelException) {
                        logger.debug("requestId={} operation=turn result=client_disconnected", call.callId)
                    }
                }
            }
        }
        get("/") { call.respondResource("ui/index.html") }
        staticResources("/", "ui", index = null)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.validLlamaCppPortOrRespond(): Int? = try {
    LlamaCppPort.parse(request.queryParameters["port"] ?: "")
} catch (_: IllegalArgumentException) {
    respond(HttpStatusCode.BadRequest, invalidRequestError())
    null
}

private suspend fun io.ktor.server.application.ApplicationCall.respondLlamaUnavailable(
    logger: org.slf4j.Logger,
    operation: String,
    failure: Throwable,
) {
    logger.warn("requestId={} operation={} result=llama_cpp_unavailable cause={}", callId, operation, failure.javaClass.simpleName)
    respond(HttpStatusCode.ServiceUnavailable, ApiError("llama_cpp_unavailable", "The model service is unavailable."))
}

private fun invalidRequestError(): ApiError = ApiError("invalid_request", "Request contains invalid data.")

object RequestValidator {
    fun validate(request: TurnRequest): ApiError? = validate(
        sessionId = request.sessionId,
        modelId = request.modelId,
        port = request.llamaCppPort,
        scenarioMarkdown = request.scenarioMarkdown,
        history = request.history,
        playerAction = request.playerAction,
    )

    fun validate(request: PrefillRequest): ApiError? = when {
        request.revision < 0 -> invalid()
        else -> validate(
            sessionId = request.sessionId,
            modelId = request.modelId,
            port = request.llamaCppPort,
            scenarioMarkdown = request.scenarioMarkdown,
            history = request.history,
            playerAction = request.playerAction,
        )
    }

    private fun validate(
        sessionId: String,
        modelId: String,
        port: Int,
        scenarioMarkdown: String,
        history: List<ChatEntry>,
        playerAction: String,
    ): ApiError? = when {
        sessionId.isBlank() || sessionId.length > MAX_SESSION_ID_LENGTH -> invalid()
        modelId.isBlank() || modelId.length > MAX_MODEL_ID_LENGTH -> invalid()
        port !in 1..65535 -> invalid()
        scenarioMarkdown.isBlank() || scenarioMarkdown.length > MAX_SCENARIO_LENGTH -> invalid()
        playerAction.isBlank() || playerAction.length > MAX_TEXT_LENGTH -> invalid()
        history.size > MAX_HISTORY_ENTRIES -> invalid()
        history.any { !it.isValidHistoryEntry() } -> invalid()
        else -> null
    }

    private fun ChatEntry.isValidHistoryEntry(): Boolean =
        role in ALLOWED_HISTORY_ROLES && content.isNotBlank() && content.length <= MAX_TEXT_LENGTH

    private fun invalid() = invalidRequestError()

    private const val MAX_SESSION_ID_LENGTH = 256
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
