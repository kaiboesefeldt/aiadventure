package de.aidadventure.server.llamacpp

import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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

        return json.decodeFromString<ModelsResponse>(response.body()).data
            .map { it.id }
            .also { check(it.isNotEmpty()) { "llama.cpp model discovery returned no models." } }
    }

    fun stream(request: TurnRequest): Flow<TurnStreamEvent> {
        val port = requirePort(request.llamaCppPort)
        val payload = CompletionRequest(
            model = request.modelId,
            messages = promptFactory.build(request).map(::toOpenAiMessage),
            stream = true,
        )

        return callbackFlow {
            val response = try {
                httpClient.send(
                    HttpRequest.newBuilder(endpoint(port, "/v1/chat/completions"))
                        .timeout(requestTimeout)
                        .header("Accept", "text/event-stream")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload)))
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            } catch (failure: Exception) {
                close(failure)
                awaitClose { }
                return@callbackFlow
            }
            if (response.statusCode() !in 200..299) {
                response.body().close()
                close(IllegalStateException("llama.cpp chat completion failed: HTTP ${response.statusCode()}"))
                awaitClose { }
                return@callbackFlow
            }

            var completed = false
            response.body().bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        trySendBlocking(TurnStreamEvent.Done(TurnMetrics(null, null, null)))
                        close()
                        completed = true
                        break
                    }

                    val chunk = try {
                        json.decodeFromString<CompletionChunk>(data)
                    } catch (failure: Exception) {
                        close(failure)
                        break
                    }
                    if (chunk.error != null) {
                        close(IllegalStateException("llama.cpp chat completion returned an error."))
                        break
                    }
                    chunk.choices.firstOrNull()?.delta?.content
                        ?.takeIf { it.isNotBlank() }
                        ?.let { token ->
                            val result = trySendBlocking(TurnStreamEvent.Token(token))
                            if (result.isFailure) {
                                close(result.exceptionOrNull() ?: IllegalStateException("Unable to deliver llama.cpp stream event."))
                            }
                    }
                }
            }
            if (!completed) close()
            awaitClose { }
        }
    }

    private fun endpoint(port: Int, path: String): URI = URI.create("http://localhost:${requirePort(port)}$path")

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
    private data class CompletionRequest(
        val model: String,
        val messages: List<OpenAiMessage>,
        val stream: Boolean,
    )

    @Serializable
    private data class OpenAiMessage(val role: String, val content: String)

    @Serializable
    private data class CompletionChunk(
        val choices: List<Choice> = emptyList(),
        val error: JsonElement? = null,
    )

    @Serializable
    private data class Choice(val delta: Delta = Delta())

    @Serializable
    private data class Delta(val content: String? = null)

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val json = Json { ignoreUnknownKeys = true }
    }
}
