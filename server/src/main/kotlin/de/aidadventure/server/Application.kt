package de.aidadventure.server

import de.aidadventure.server.game.ContextEpochs
import de.aidadventure.server.game.ContextLimitException
import de.aidadventure.server.model.ContextCompression
import de.aidadventure.server.llamacpp.LlamaCppGateway
import de.aidadventure.server.llamacpp.LlamaCppPort
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.PrefillAccepted
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.StreamDone
import de.aidadventure.server.model.StreamError
import de.aidadventure.server.model.StreamFragment
import de.aidadventure.server.model.StreamToken
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.model.WarmupRequest
import de.aidadventure.server.worldstate.WorldStateResolutionRequest
import de.aidadventure.server.prefill.CoordinatorKey
import de.aidadventure.server.prefill.ExpiringPrefillRegistry
import de.aidadventure.server.prefill.LlamaCppPrefillGateway
import de.aidadventure.server.prefill.PrefillRegistry
import de.aidadventure.server.runtimefragments.DefaultRuntimeFragmentResolver
import de.aidadventure.server.runtimefragments.RuntimeFragmentResolver
import de.aidadventure.server.embedding.OnnxLocalEmbeddingService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    val config = ServerRuntimeConfig.from(System.getenv(), args)
    ServerLogging.configure(config.logLevel)
    embeddedServer(Netty, port = config.port, host = config.host) {
        adventureModule(
            llamaCppGateway = LlamaCppGateway(contextCompactionThresholdPercent = config.contextCompactionThresholdPercent),
            llamaCppPort = config.llamaCppPort,
        )
    }.start(wait = true)
}

data class ServerRuntimeConfig(
    val host: String,
    val port: Int,
    val llamaCppPort: Int,
    val logLevel: String,
    val contextCompactionThresholdPercent: Int,
) {
    companion object {
        fun from(environment: Map<String, String>, args: Array<String> = emptyArray()): ServerRuntimeConfig {
            val exposeLan = environment["ADVENTURE_EXPOSE_LAN"] ?: "false"
            require(exposeLan == "true" || exposeLan == "false") {
                "ADVENTURE_EXPOSE_LAN must be either true or false."
            }
            val options = parseOptions(args)
            val contextThreshold = environment["CONTEXT_COMPACTION_THRESHOLD"]?.let { configured ->
                configured.toIntOrNull() ?: throw IllegalArgumentException(
                    "CONTEXT_COMPACTION_THRESHOLD must be an integer between 1 and 99.",
                )
            } ?: 65
            require(contextThreshold in 1..99) {
                "CONTEXT_COMPACTION_THRESHOLD must be an integer between 1 and 99."
            }
            return ServerRuntimeConfig(
                host = if (exposeLan == "true") "0.0.0.0" else "127.0.0.1",
                port = environment["PORT"]?.toIntOrNull() ?: 8080,
                llamaCppPort = options.llamaCppPort,
                logLevel = options.logLevel,
                contextCompactionThresholdPercent = contextThreshold,
            )
        }

        private fun parseOptions(args: Array<String>): RuntimeOptions {
            var llamaCppPort: Int? = null
            var logLevel: String? = null
            for (argument in args) {
                when {
                    argument.startsWith("--llama-port=") -> {
                        require(llamaCppPort == null) { "--llama-port may only be specified once." }
                        llamaCppPort = LlamaCppPort.parse(argument.removePrefix("--llama-port="))
                    }

                    argument.startsWith("--log-level=") -> {
                        require(logLevel == null) { "--log-level may only be specified once." }
                        logLevel = argument.removePrefix("--log-level=").lowercase(Locale.ROOT)
                        require(logLevel in LOG_LEVELS) { "--log-level must be TRACE, DEBUG, INFO, WARN, or ERROR." }
                    }
                    else -> require(false) { "Usage: --llama-port=<1-65535> --log-level=<TRACE|DEBUG|INFO|WARN|ERROR>." }
                }
            }
            return RuntimeOptions(
                llamaCppPort = llamaCppPort ?: LlamaCppPort.DEFAULT,
                logLevel = logLevel ?: "info",
            )
        }

        private data class RuntimeOptions(val llamaCppPort: Int, val logLevel: String)

        private val LOG_LEVELS = setOf("trace", "debug", "info", "warn", "error")
    }
}

fun Application.adventureModule(
    contextCompactionThresholdPercent: Int = 65,
    llamaCppGateway: LlamaCppGateway = LlamaCppGateway(contextCompactionThresholdPercent = contextCompactionThresholdPercent),
    prefillRegistry: PrefillRegistry = ExpiringPrefillRegistry(LlamaCppPrefillGateway(llamaCppGateway)),
    runtimeFragmentResolver: RuntimeFragmentResolver = ProductionRuntimeFragmentResolver(),
    clock: () -> Long = System::currentTimeMillis,
    llamaCppPort: Int = LlamaCppPort.DEFAULT,
) {
    require(contextCompactionThresholdPercent in 1..99) {
        "Context compaction threshold must be between 1 and 99 percent."
    }
    val logger = LoggerFactory.getLogger("de.aidadventure.server.AdventureApi")
    val postTurnScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    monitor.subscribe(ApplicationStopped) { postTurnScope.cancel() }
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
        post("/api/prefill") {
            val request = call.receive<PrefillRequest>().configuredFor(llamaCppPort)
            RequestValidator.validate(request)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, error)
                return@post
            }
            val key = CoordinatorKey(request.sessionId, request.modelId, request.llamaCppPort)
            try {
                prefillRegistry.removeIdle(clock())
                call.respond(
                    HttpStatusCode.Accepted,
                    PrefillAccepted(prefillRegistry.coordinatorFor(key).submit(request)),
                )
            } catch (failure: ContextLimitException) {
                logger.warn("requestId={} operation=prefill result=context_limit", call.callId)
                call.respond(HttpStatusCode.UnprocessableEntity, ApiError("context_limit", failure.message ?: "The model context is full."))
            } catch (failure: IOException) {
                call.respondLlamaUnavailable(logger, "prefill", failure)
            } catch (failure: RuntimeException) {
                if (failure is CancellationException) throw failure
                call.respondLlamaUnavailable(logger, "prefill", failure)
            }
        }
        post("/api/warmup") {
            val request = call.receive<WarmupRequest>().configuredFor(llamaCppPort)
            RequestValidator.validate(request)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, error)
                return@post
            }
            try {
                runtimeFragmentResolver.prepare(request.runtimeFragments)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                logger.warn("requestId={} operation={} failure={}", call.callId, "warmup", failure.javaClass.simpleName)
            }
            val key = CoordinatorKey(request.sessionId, request.modelId, request.llamaCppPort)
            try {
                prefillRegistry.removeIdle(clock())
                call.respond(HttpStatusCode.Accepted, PrefillAccepted(prefillRegistry.coordinatorFor(key).warmup(request, call.callId ?: "unknown")))
            } catch (failure: ContextLimitException) {
                logger.warn("requestId={} operation=warmup result=context_limit", call.callId)
                call.respond(HttpStatusCode.UnprocessableEntity, ApiError("context_limit", failure.message ?: "The model context is full."))
            } catch (failure: IOException) {
                call.respondLlamaUnavailable(logger, "warmup", failure)
            } catch (failure: RuntimeException) {
                if (failure is CancellationException) throw failure
                call.respondLlamaUnavailable(logger, "warmup", failure)
            }
        }
        post("/api/turn") {
            val request = call.receive<TurnRequest>().configuredFor(llamaCppPort)
            RequestValidator.validate(request)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, error)
                return@post
            }
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    sseComment("connected")
                    prefillRegistry.removeIdle(clock())
                    val coordinator = prefillRegistry
                        .coordinatorFor(CoordinatorKey(request.sessionId, request.modelId, request.llamaCppPort))
                    val resolved = coordinator.resolvedTurnFor(request) ?: ResolvedTurn(request, null)
                    val stream = coordinator
                        .beginTurn(resolved.request, call.callId ?: "unknown")
                    val generated = StringBuilder()
                    var completedMetrics: TurnMetrics? = null
                    var completedCompression = request.contextCompression
                    var inputReady = false
                    var appliedFragment: String? = resolved.fragment
                    stream.collect { event ->
                        when (event) {
                            is TurnStreamEvent.Token -> {
                                generated.append(event.text)
                                sse("token", eventJson.encodeToString(StreamToken(event.text)))
                            }
                            is TurnStreamEvent.Fragment -> {
                                appliedFragment = event.fragment
                                sse("fragment", eventJson.encodeToString(StreamFragment(event.fragment)))
                            }
                            TurnStreamEvent.GenerationReady -> sse("generation_ready", "{}")
                            TurnStreamEvent.InputReady -> {
                                sse("input_ready", "{}")
                                inputReady = true
                            }
                            is TurnStreamEvent.Done -> {
                                completedMetrics = event.metrics
                                completedCompression = event.contextCompression
                            }
                        }
                    }
                    val metrics = completedMetrics
                    if (generated.isNotEmpty() && metrics != null) {
                        if (!inputReady) sse("input_ready", "{}")
                        val requestId = call.callId ?: "unknown"
                        val completedHistory = (appliedFragment?.let { request.history + ChatEntry("runtime-fragment", it) } ?: request.history) +
                            ChatEntry("player", request.playerAction) +
                            ChatEntry("game-master", generated.toString())
                        val warmupRequest = WarmupRequest(
                            sessionId = request.sessionId,
                            modelId = request.modelId,
                            llamaCppPort = request.llamaCppPort,
                            scenarioMarkdown = request.scenarioMarkdown,
                            history = completedHistory,
                            runtimeFragments = request.runtimeFragments,
                            worldState = request.worldState,
                            contextCompression = completedCompression,
                            locations = request.locations,
                        )
                        val acceptedWorldState = coordinator.resolveWorldStateAndWarmup(
                            resolved.request.copy(contextCompression = completedCompression),
                            generated.toString(),
                            warmupRequest,
                            WorldStateResolutionRequest(request.sessionId, request.scenarioMarkdown, request.worldState, request.playerAction, generated.toString(), request.locations),
                            requestId,
                        )
                        if (logger.isTraceEnabled) {
                            logger.trace("operation=world_state phase=accepted sessionId={}", request.sessionId)
                        }
                        val acceptedWarmupRequest = warmupRequest.copy(worldState = acceptedWorldState)
                        var reservation: de.aidadventure.server.prefill.RerankReservation? = null
                        try {
                            reservation = try {
                                coordinator.reserveRerank(acceptedWarmupRequest, requestId)
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                logger.warn(
                                    "requestId={} operation={} failure={}",
                                    requestId,
                                    "post_turn_fragment_rerank",
                                    failure.javaClass.simpleName,
                                )
                                null
                            }
                            sse("done", eventJson.encodeToString(StreamDone(metrics, acceptedWorldState, completedCompression)))
                            reservation?.let { rerankReservation ->
                                postTurnScope.launch {
                                    var candidate: de.aidadventure.server.model.RuntimeFragmentDefinition? = null
                                    try {
                                        if (acceptedWorldState.location.isNotBlank() || acceptedWorldState.narrativeFocus.isNotBlank()) {
                                            candidate = runtimeFragmentResolver.resolve(
                                                completedHistory,
                                                request.runtimeFragments,
                                                acceptedWorldState.narrativeFocus,
                                            )
                                        }
                                    } catch (failure: Exception) {
                                        if (failure is CancellationException) throw failure
                                        logger.warn(
                                            "requestId={} operation={} failure={}",
                                            requestId,
                                            "post_turn_fragment_rerank",
                                            failure.javaClass.simpleName,
                                        )
                                    } finally {
                                        withContext(NonCancellable) {
                                            coordinator.completeReservedRerank(rerankReservation, candidate)
                                        }
                                    }
                                }.invokeOnCompletion { rerankReservation.complete(null) }
                            }
                        } catch (failure: Exception) {
                            reservation?.let { rerankReservation ->
                                withContext(NonCancellable) {
                                    coordinator.completeReservedRerank(rerankReservation, null)
                                }
                            }
                            throw failure
                        }
                    } else if (metrics != null) {
                        sse("done", eventJson.encodeToString(StreamDone(metrics, request.worldState, completedCompression)))
                    }
                } catch (_: ClosedWriteChannelException) {
                    logger.debug("requestId={} operation=turn result=client_disconnected", call.callId)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: ContextLimitException) {
                    logger.warn("requestId={} operation=turn result=context_limit", call.callId)
                    try {
                        sse("error", eventJson.encodeToString(StreamError("context_limit", failure.message ?: "The model context is full.")))
                    } catch (_: ClosedWriteChannelException) {
                        logger.debug("requestId={} operation=turn result=client_disconnected", call.callId)
                    }
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

private const val CONFIGURED_MODEL_ID = "configured-llama-cpp"

data class ResolvedTurn(val request: TurnRequest, val fragment: String?)

private fun TurnRequest.configuredFor(port: Int) = copy(modelId = CONFIGURED_MODEL_ID, llamaCppPort = port)
private fun PrefillRequest.configuredFor(port: Int) = copy(modelId = CONFIGURED_MODEL_ID, llamaCppPort = port)
private fun WarmupRequest.configuredFor(port: Int) = copy(modelId = CONFIGURED_MODEL_ID, llamaCppPort = port)

internal class ProductionRuntimeFragmentResolver(
    private val delegateFactory: () -> RuntimeFragmentResolver = {
        DefaultRuntimeFragmentResolver(OnnxLocalEmbeddingService.create())
    },
) : RuntimeFragmentResolver {
    private val delegate by lazy(delegateFactory)

    override suspend fun prepare(definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>) {
        if (definitions.isNotEmpty()) delegate.prepare(definitions)
    }

    override suspend fun resolve(
        history: List<ChatEntry>,
        definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
    ): de.aidadventure.server.model.RuntimeFragmentDefinition? =
        resolve(history, definitions, null)

    override suspend fun resolve(
        history: List<ChatEntry>,
        definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
        narrativeFocus: String?,
    ): de.aidadventure.server.model.RuntimeFragmentDefinition? =
        if (definitions.isEmpty()) null else delegate.resolve(history, definitions, narrativeFocus)
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
        runtimeFragments = request.runtimeFragments,
        worldState = request.worldState,
        contextCompression = request.contextCompression,
        locations = request.locations,
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
            runtimeFragments = request.runtimeFragments,
            worldState = request.worldState,
            contextCompression = request.contextCompression,
            locations = request.locations,
        )
    }

    fun validate(request: WarmupRequest): ApiError? = validateContext(
        sessionId = request.sessionId,
        modelId = request.modelId,
        port = request.llamaCppPort,
        scenarioMarkdown = request.scenarioMarkdown,
        history = request.history,
        runtimeFragments = request.runtimeFragments,
        worldState = request.worldState,
        contextCompression = request.contextCompression,
        locations = request.locations,
    )

    private fun validate(
        sessionId: String,
        modelId: String,
        port: Int,
        scenarioMarkdown: String,
        history: List<ChatEntry>,
        playerAction: String,
        runtimeFragments: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
        worldState: de.aidadventure.server.worldstate.WorldState,
        contextCompression: ContextCompression,
        locations: Map<String, String>,
    ): ApiError? = when {
        validateContext(sessionId, modelId, port, scenarioMarkdown, history, runtimeFragments, worldState, contextCompression, locations) != null -> invalid()
        playerAction.isBlank() || playerAction.length > MAX_TEXT_LENGTH -> invalid()
        else -> null
    }

    private fun validateContext(
        sessionId: String,
        modelId: String,
        port: Int,
        scenarioMarkdown: String,
        history: List<ChatEntry>,
        runtimeFragments: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
        worldState: de.aidadventure.server.worldstate.WorldState,
        contextCompression: ContextCompression,
        locations: Map<String, String>,
    ): ApiError? = when {
        sessionId.isBlank() || sessionId.length > MAX_SESSION_ID_LENGTH -> invalid()
        modelId.isBlank() || modelId.length > MAX_MODEL_ID_LENGTH -> invalid()
        port !in 1..65535 -> invalid()
        scenarioMarkdown.isBlank() || scenarioMarkdown.length > MAX_SCENARIO_LENGTH -> invalid()
        history.size > MAX_HISTORY_ENTRIES -> invalid()
        history.any { !it.isValidHistoryEntry() } -> invalid()
        history.sumOf { it.content.length.toLong() } > MAX_HISTORY_CHARS -> invalid()
        runCatching { ContextEpochs.validate(sessionId, scenarioMarkdown, history, modelId, contextCompression, locations) }.isFailure -> invalid()
        runtimeFragments.size > MAX_RUNTIME_FRAGMENT_DEFINITIONS -> invalid()
        runtimeFragments.any { !it.isValidRuntimeFragmentDefinition() } -> invalid()
        runtimeFragments.map { it.matchText }.toSet().size != runtimeFragments.size -> invalid()
        else -> null
    }

    private fun ChatEntry.isValidHistoryEntry(): Boolean =
        role in ALLOWED_HISTORY_ROLES && content.isNotBlank() && content.length <= MAX_TEXT_LENGTH

    private fun de.aidadventure.server.model.RuntimeFragmentDefinition.isValidRuntimeFragmentDefinition(): Boolean =
        matchText.isNotBlank() && fragment.isNotBlank() &&
            matchText.length <= MAX_TEXT_LENGTH && fragment.length <= MAX_TEXT_LENGTH

    private fun invalid() = invalidRequestError()

    private const val MAX_SESSION_ID_LENGTH = 256
    private const val MAX_MODEL_ID_LENGTH = 256
    private const val MAX_SCENARIO_LENGTH = 200_000
    private const val MAX_TEXT_LENGTH = 20_000
    private const val MAX_HISTORY_ENTRIES = 100_000
    private const val MAX_HISTORY_CHARS = 20_000_000L
    private const val MAX_RUNTIME_FRAGMENT_DEFINITIONS = 200
    private val ALLOWED_HISTORY_ROLES = setOf("player", "user", "game-master", "assistant", "ai", "runtime-fragment")
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
