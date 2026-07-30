package de.aidadventure.server.worldstate

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

data class WorldStateResolutionRequest(val sessionId: String, val scenarioMarkdown: String, val previousState: WorldState, val playerAction: String, val gameMasterResponse: String)
fun interface WorldStateResolver { suspend fun resolve(request: WorldStateResolutionRequest): WorldState? }

class LlamaCppWorldStateResolver(private val port: Int, private val client: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) : WorldStateResolver {
    override suspend fun resolve(request: WorldStateResolutionRequest): WorldState? = runInterruptible(Dispatchers.IO) {
        val resolvedLocation = if (request.previousState.location.isBlank()) {
            locationFrom(request, "initial_location")
        } else when (complete(MOVEMENT_PROMPT + "\nPLAYER ACTION:\n${request.playerAction}\nGAME-MASTER RESPONSE:\n${request.gameMasterResponse}", movementSchema, request, "movement")?.let(::movement)) {
            "move" -> locationFrom(request, "moved_location")
            else -> null
        }
        val intermediateState = resolvedLocation?.let { request.previousState.copy(location = it.location) } ?: request.previousState
        val activeThread = complete(
            THREAD_PROMPT + "\nPRIOR THREAD:\n${request.previousState.activeThread}\nPLAYER ACTION:\n${request.playerAction}\nGAME-MASTER RESPONSE:\n${request.gameMasterResponse}",
            threadSchema,
            request,
            "thread",
        )?.let(::thread) ?: return@runInterruptible intermediateState.takeIf { it.location != request.previousState.location }
        if (intermediateState.location == request.previousState.location && activeThread == request.previousState.activeThread) null
        else intermediateState.copy(activeThread = activeThread)
    }

    private fun locationFrom(request: WorldStateResolutionRequest, phase: String): WorldState? = complete(
        LOCATION_PROMPT + "\nGAME-MASTER RESPONSE:\n${request.gameMasterResponse}", locationSchema, request, phase,
    )?.let(::location)?.takeIf { it.length in 1..96 && occursIn(it, request.gameMasterResponse) }?.let(::WorldState)

    private fun complete(prompt: String, schema: JsonObject, request: WorldStateResolutionRequest, phase: String): String? = try {
        val response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:$port/completion"))
            .timeout(TIMEOUT).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(CompletionRequest(prompt, 32, false, schema))))
            .build(), HttpResponse.BodyHandlers.ofString())
        if (logger.isTraceEnabled) logger.trace("operation=world_state phase={} requestId={}", phase, request.sessionId)
        if (response.statusCode() !in 200..299) {
            logger.warn("operation=world_state phase={} requestId={} status={}", phase, request.sessionId, response.statusCode())
            null
        } else json.decodeFromString<CompletionResponse>(response.body()).content
    } catch (error: InterruptedException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.warn("operation=world_state phase={} requestId={} cause={}", phase, request.sessionId, error.javaClass.simpleName)
        null
    }

    private fun movement(content: String): String? = runCatching { resultJson.decodeFromString<MovementResult>(content).movement }.getOrNull()
    private fun location(content: String): String? = runCatching { resultJson.decodeFromString<LocationResult>(content).location }.getOrNull()
        ?.trim()
        ?.trimEnd(',', ';', ':', '.', '!', '?')
        ?.trim()
        ?.takeIf(String::isNotBlank)
    private fun thread(content: String): String? = runCatching { resultJson.decodeFromString<ThreadResult>(content).activeThread }.getOrNull()
        ?.trim()
        ?.takeIf { it.length <= 96 }
    private fun occursIn(candidate: String, response: String): Boolean = Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(candidate)}(?![\\p{L}\\p{N}])").containsMatchIn(response)

    @Serializable private data class CompletionRequest(val prompt: String, @SerialName("n_predict") val nPredict: Int, val stream: Boolean, @SerialName("json_schema") val jsonSchema: JsonObject)
    @Serializable private data class CompletionResponse(val content: String? = null)
    @Serializable private data class MovementResult(val movement: String)
    @Serializable private data class LocationResult(val location: String)
    @Serializable private data class ThreadResult(val activeThread: String)
    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(300)
        val json = Json { ignoreUnknownKeys = true }
        val resultJson = Json
        val logger = LoggerFactory.getLogger(LlamaCppWorldStateResolver::class.java)
        val movementSchema = schema("""{"type":"object","additionalProperties":false,"required":["movement"],"properties":{"movement":{"enum":["stay","move"]}}}""")
        val locationSchema = schema("""{"type":"object","additionalProperties":false,"required":["location"],"properties":{"location":{"type":"string","minLength":1,"maxLength":48}}}""")
        val threadSchema = schema("""{"type":"object","additionalProperties":false,"required":["activeThread"],"properties":{"activeThread":{"type":"string","maxLength":96}}}""")
        fun schema(source: String) = json.parseToJsonElement(source).jsonObject
        const val MOVEMENT_PROMPT = "Decide whether the player character leaves the current scene."
        const val LOCATION_PROMPT = "Name the current scene location established in the game-master response. Return exactly one short location name; never list alternatives, scores, explanations, or punctuation."
        const val THREAD_PROMPT = "Retain, replace, or clear the one dominant unresolved task, danger, or obligation established by the inputs. Do not invent plot, explanations, or alternatives. Return only the active thread."
    }
}
