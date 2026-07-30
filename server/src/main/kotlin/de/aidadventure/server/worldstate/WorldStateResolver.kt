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
        val unresolvedSituation = complete(
            SITUATION_PROMPT + "\nPRIOR UNRESOLVED SITUATION:\n${request.previousState.unresolvedSituation}\nPLAYER ACTION:\n${request.playerAction}\nGAME-MASTER RESPONSE:\n${request.gameMasterResponse}",
            situationSchema,
            request,
            "unresolved_situation",
        )?.let(::unresolvedSituation) ?: request.previousState.unresolvedSituation
        request.previousState.copy(
            location = resolvedLocation?.location ?: request.previousState.location,
            unresolvedSituation = unresolvedSituation,
        ).takeIf { it != request.previousState }
    }

    private fun locationFrom(request: WorldStateResolutionRequest, phase: String): WorldState? = complete(
        LOCATION_PROMPT + "\nGAME-MASTER RESPONSE:\n${request.gameMasterResponse}", locationSchema, request, phase,
    )?.let(::location)?.takeIf { it.length in 1..96 && occursIn(it, request.gameMasterResponse) }?.let(::WorldState)

    private fun complete(prompt: String, schema: JsonObject, request: WorldStateResolutionRequest, phase: String): String? = try {
        val response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:$port/completion"))
            .timeout(TIMEOUT).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(CompletionRequest(prompt, false, schema))))
            .build(), HttpResponse.BodyHandlers.ofString())
        if (logger.isTraceEnabled) logger.trace("operation=world_state phase={} requestId={} {}", phase, request.sessionId, secondaryOutputTrace(response.body()))
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
    private fun unresolvedSituation(content: String): String? = runCatching { resultJson.decodeFromString<UnresolvedSituationResult>(content).unresolvedSituation }.getOrNull()
        ?.trim()
        ?.takeUnless { it.isNotEmpty() && it.all(Char::isDigit) }
    private fun occursIn(candidate: String, response: String): Boolean = Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(candidate)}(?![\\p{L}\\p{N}])").containsMatchIn(response)

    @Serializable private data class CompletionRequest(val prompt: String, val stream: Boolean, @SerialName("json_schema") val jsonSchema: JsonObject)
    @Serializable private data class CompletionResponse(val content: String? = null)
    @Serializable private data class MovementResult(val movement: String)
    @Serializable private data class LocationResult(val location: String)
    @Serializable private data class UnresolvedSituationResult(val unresolvedSituation: String)
    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(300)
        val json = Json { ignoreUnknownKeys = true }
        val resultJson = Json
        val logger = LoggerFactory.getLogger(LlamaCppWorldStateResolver::class.java)
        val movementSchema = schema("""{"type":"object","additionalProperties":false,"required":["movement"],"properties":{"movement":{"enum":["stay","move"]}}}""")
        val locationSchema = schema("""{"type":"object","additionalProperties":false,"required":["location"],"properties":{"location":{"type":"string","minLength":1,"maxLength":48}}}""")
        val situationSchema = schema("""{"type":"object","additionalProperties":false,"required":["unresolvedSituation"],"properties":{"unresolvedSituation":{"anyOf":[{"const":""},{"type":"string","minLength":3,"maxLength":160}]}}}""")
        fun schema(source: String) = json.parseToJsonElement(source).jsonObject
        const val MOVEMENT_PROMPT = "Decide whether the player character leaves the current scene."
        const val LOCATION_PROMPT = "Name the current scene location established in the game-master response. Return exactly one short location name; never list alternatives, scores, explanations, or punctuation."
        const val SITUATION_PROMPT = "Return only one complete sentence (max 20 words) naming the single unresolved goal, danger, or obligation. Never describe characters, places, atmosphere, or background. Return \"\" if none. No metadata, menu numbers, labels, invention, explanations, or alternatives."
    }
}

internal fun secondaryOutputTrace(output: String): String = "secondaryOutput=$output"
