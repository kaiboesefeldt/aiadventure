package de.aidadventure.server.llamacpp

import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class CacheMetrics(val cachedTokens: Int?, val evaluatedTokens: Int?)

@OptIn(ExperimentalSerializationApi::class)
class LlamaCppGateway(
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
    private val promptFactory: PromptFactory = PromptFactory(),
    private val requestTimeout: Duration = DEFAULT_TIMEOUT,
) {
    fun availableModels(port: Int): List<String> {
        val response = httpClient.send(
            HttpRequest.newBuilder(endpoint(port, "/v1/models"))
                .timeout(requestTimeout)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "llama.cpp model discovery failed: HTTP ${response.statusCode()}" }

        val firstModel = json.decodeFromString<ModelsResponse>(response.body()).data.firstOrNull()
            ?: throw IllegalStateException("llama.cpp model discovery returned no models.")
        val modelId = firstModel.id
        check(modelId.isNotBlank()) { "llama.cpp model discovery returned a blank model ID." }
        return listOf(modelId)
    }

    fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot> = emptySet()): LlamaCppSlot {
        val response = httpClient.send(
            HttpRequest.newBuilder(endpoint(port, "/slots"))
                .timeout(requestTimeout)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "llama.cpp slot discovery failed: HTTP ${response.statusCode()}" }
        val slot = json.decodeFromString<List<SlotResponse>>(response.body()).firstOrNull {
            !it.isProcessing && LlamaCppSlot(it.id) !in excludedSlots
        } ?: throw IllegalStateException("llama.cpp has no idle slot.")
        val leasedSlot = LlamaCppSlot(slot.id)
        eraseSlot(port, leasedSlot)
        return leasedSlot
    }

    fun releaseSlot(port: Int, slot: LlamaCppSlot) {
        eraseSlot(port, slot)
    }

    suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics = runInterruptible {
        val prompt = applyTemplate(request)
        val response = postCompletion(
            request.llamaCppPort,
            CompletionRequest(prompt, slot.id, predictedTokens = 0, stream = false),
        )
        CacheMetrics(response.tokensCached, response.tokensEvaluated)
    }

    fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> = callbackFlow {
        val responseBody = ResponseBodyOwner()
        val reader = launch(Dispatchers.IO) {
            try {
                val prompt = applyTemplate(request)
                val response = runInterruptible {
                    val received = httpClient.send(
                        HttpRequest.newBuilder(endpoint(requirePort(request.llamaCppPort), "/completion"))
                            .header("Accept", "text/event-stream")
                            .header("Content-Type", "application/json")
                            .POST(
                                HttpRequest.BodyPublishers.ofString(
                                    json.encodeToString(
                                        CompletionRequest(prompt, slot.id, predictedTokens = -1, stream = true),
                                    ),
                                ),
                            )
                            .build(),
                        HttpResponse.BodyHandlers.ofInputStream(),
                    )
                    received.takeIf { responseBody.publish(it.body()) }
                } ?: return@launch
                if (!isActive) return@launch
                if (response.statusCode() !in 200..299) {
                    close(IllegalStateException("llama.cpp completion failed: HTTP ${response.statusCode()}"))
                    return@launch
                }

                var completed = false
                val lines = response.body().bufferedReader()
                while (true) {
                    val line = lines.readLine() ?: break
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    val chunk = json.decodeFromString<NativeCompletionChunk>(data)
                    if (chunk.error != null) {
                        throw IllegalStateException("llama.cpp completion returned an error.")
                    }
                    chunk.content?.takeIf { it.isNotEmpty() }?.let { token -> send(TurnStreamEvent.Token(token)) }
                    if (chunk.stop) {
                        send(TurnStreamEvent.Done(TurnMetrics(null, chunk.tokensEvaluated, chunk.tokensCached)))
                        completed = true
                        close()
                        break
                    }
                }
                if (!completed) {
                    close(IllegalStateException("llama.cpp completion ended before stop."))
                }
            } catch (_: CancellationException) {
                // Collector cancellation is expected and is handled by awaitClose below.
            } catch (failure: Exception) {
                if (isActive) close(failure)
            } finally {
                responseBody.close()
            }
        }
        awaitClose {
            try {
                responseBody.close()
            } finally {
                reader.cancel()
            }
        }
    }

    fun stream(request: TurnRequest): Flow<TurnStreamEvent> {
        val port = requirePort(request.llamaCppPort)
        val payload = ChatCompletionRequest(
            model = request.modelId,
            messages = promptFactory.build(request).map(::toOpenAiMessage),
            stream = true,
        )

        return callbackFlow {
            val responseBody = ResponseBodyOwner()
            val reader = launch(Dispatchers.IO) {
                try {
                    val response = runInterruptible {
                        val received = httpClient.send(
                            HttpRequest.newBuilder(endpoint(port, "/v1/chat/completions"))
                                .header("Accept", "text/event-stream")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload)))
                                .build(),
                            HttpResponse.BodyHandlers.ofInputStream(),
                        )
                        received.takeIf { responseBody.publish(it.body()) }
                    }
                        ?: return@launch
                    if (!isActive) return@launch
                    if (response.statusCode() !in 200..299) {
                        close(IllegalStateException("llama.cpp chat completion failed: HTTP ${response.statusCode()}"))
                        return@launch
                    }

                    var completed = false
                    val lines = response.body().bufferedReader()
                    while (true) {
                        val line = lines.readLine() ?: break
                        if (!line.startsWith("data:")) continue

                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") {
                            send(TurnStreamEvent.Done(TurnMetrics(null, null, null)))
                            completed = true
                            close()
                            break
                        }

                        val chunk = json.decodeFromString<CompletionChunk>(data)
                        if (chunk.error != null) {
                            throw IllegalStateException("llama.cpp chat completion returned an error.")
                        }
                        chunk.choices.firstOrNull()?.delta?.content
                            ?.let { token -> send(TurnStreamEvent.Token(token)) }
                    }
                    if (!completed) {
                        close(IllegalStateException("llama.cpp chat completion ended before [DONE]."))
                    }
                } catch (_: CancellationException) {
                    // Collector cancellation is expected and is handled by awaitClose below.
                } catch (failure: Exception) {
                    if (isActive) close(failure)
                } finally {
                    responseBody.close()
                }
            }
            awaitClose {
                try {
                    responseBody.close()
                } finally {
                    reader.cancel()
                }
            }
        }
    }

    private fun endpoint(port: Int, path: String): URI = URI.create("http://localhost:${requirePort(port)}$path")

    private fun applyTemplate(request: TurnRequest): String {
        val response = httpClient.send(
            HttpRequest.newBuilder(endpoint(requirePort(request.llamaCppPort), "/apply-template"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(ApplyTemplateRequest(promptFactory.build(request).map(::toOpenAiMessage))),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "llama.cpp template application failed: HTTP ${response.statusCode()}" }
        return json.decodeFromString<ApplyTemplateResponse>(response.body()).prompt
            .also { check(it.isNotBlank()) { "llama.cpp template application returned a blank prompt." } }
    }

    private fun postCompletion(port: Int?, payload: CompletionRequest): NativeCompletionResponse {
        val response = httpClient.send(
            HttpRequest.newBuilder(endpoint(requirePort(port), "/completion"))
                .timeout(PREFILL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "llama.cpp completion failed: HTTP ${response.statusCode()}" }
        return json.decodeFromString(response.body())
    }

    private fun eraseSlot(port: Int, slot: LlamaCppSlot) {
        val response = httpClient.send(
            HttpRequest.newBuilder(endpoint(port, "/slots/${slot.id}?action=erase"))
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        check(response.statusCode() in 200..299) { "llama.cpp slot erase failed: HTTP ${response.statusCode()}" }
    }

    private fun requirePort(port: Int?): Int = requireNotNull(port) { "llama.cpp port is required." }
        .also { require(it in 1..65535) { "llama.cpp port must be between 1 and 65535." } }

    private fun toOpenAiMessage(message: ChatMessage): OpenAiMessage = when (message) {
        is SystemMessage -> OpenAiMessage("system", message.text())
        is UserMessage -> OpenAiMessage("user", message.singleText())
        is AiMessage -> OpenAiMessage("assistant", message.text())
        else -> throw IllegalArgumentException("Unsupported prompt message type: ${message.type()}")
    }

    @Serializable
    private data class ModelsResponse(val data: List<ModelData>)

    @Serializable
    private data class ModelData(val id: String)

    @Serializable
    private data class SlotResponse(
        val id: Int,
        @SerialName("is_processing") val isProcessing: Boolean,
        @SerialName("id_task") val taskId: Int? = null,
    )

    @Serializable
    private data class ApplyTemplateRequest(val messages: List<OpenAiMessage>)

    @Serializable
    private data class ApplyTemplateResponse(val prompt: String)

    @Serializable
    private data class CompletionRequest(
        val prompt: String,
        @SerialName("id_slot") val slotId: Int,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        @SerialName("cache_prompt") val cachePrompt: Boolean = true,
        @SerialName("n_predict") val predictedTokens: Int,
        val stream: Boolean,
    )

    @Serializable
    private data class NativeCompletionResponse(
        @SerialName("tokens_cached") val tokensCached: Int? = null,
        @SerialName("tokens_evaluated") val tokensEvaluated: Int? = null,
    )

    @Serializable
    private data class OpenAiMessage(val role: String, val content: String)

    @Serializable
    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<OpenAiMessage>,
        val stream: Boolean,
    )

    @Serializable
    private data class CompletionChunk(
        val choices: List<Choice> = emptyList(),
        val error: JsonElement? = null,
    )

    @Serializable
    private data class Choice(val delta: Delta = Delta())

    @Serializable
    private data class Delta(val content: String? = null)

    @Serializable
    private data class NativeCompletionChunk(
        val content: String? = null,
        val stop: Boolean = false,
        @SerialName("tokens_cached") val tokensCached: Int? = null,
        @SerialName("tokens_evaluated") val tokensEvaluated: Int? = null,
        val error: JsonElement? = null,
    )

    private class ResponseBodyOwner {
        private val state = AtomicReference<State>(State.Awaiting)

        fun publish(body: InputStream): Boolean {
            while (true) {
                when (state.get()) {
                    State.Awaiting -> if (state.compareAndSet(State.Awaiting, State.Published(body))) return true
                    State.Closed -> {
                        body.close()
                        return false
                    }
                    is State.Published -> error("llama.cpp response body was published twice.")
                }
            }
        }

        fun close() {
            (state.getAndSet(State.Closed) as? State.Published)?.body?.close()
        }

        private sealed interface State {
            data object Awaiting : State
            data object Closed : State
            data class Published(val body: InputStream) : State
        }
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val PREFILL_TIMEOUT: Duration = Duration.ofSeconds(60)
        val json = Json { ignoreUnknownKeys = true }
    }
}
