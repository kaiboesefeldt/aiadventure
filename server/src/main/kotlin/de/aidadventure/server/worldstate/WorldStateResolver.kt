package de.aidadventure.server.worldstate

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class WorldStateResolutionRequest(
    val sessionId: String,
    val scenarioMarkdown: String,
    val previousState: WorldState,
    val playerAction: String,
    val gameMasterResponse: String,
)

fun interface WorldStateResolver { suspend fun resolve(request: WorldStateResolutionRequest): WorldState? }

class LlamaCppWorldStateResolver(
    private val port: Int,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
) : WorldStateResolver {
    override suspend fun resolve(request: WorldStateResolutionRequest): WorldState? = runInterruptible(Dispatchers.IO) {
        when (singleFinal(complete(MOVEMENT_PROMPT + "\nPLAYER ACTION:\n${request.playerAction}\nGAME-MASTER RESPONSE:\n${request.gameMasterResponse}"))) {
            "stay" -> null
            "move" -> {
                val location = singleFinal(complete(LOCATION_PROMPT + "\nGAME-MASTER RESPONSE:\n${request.gameMasterResponse}"))
                    ?.takeIf { it.length in 1..96 && occursIn(it, request.gameMasterResponse) }
                location?.let(::WorldState)
            }
            else -> null
        }
    }

    private fun complete(prompt: String): String? = try {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/completion"))
                .timeout(TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(CompletionRequest(prompt, nPredict = 32, stream = false))))
                .build(), HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) null else json.decodeFromString<CompletionResponse>(response.body()).content
    } catch (_: Exception) { null }

    private fun singleFinal(text: String?): String? {
        val lines = text?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
        val finals = lines.filter { it.startsWith("FINAL:") }
        return finals.singleOrNull()?.takeIf { it == lines.lastOrNull() }?.removePrefix("FINAL:")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun occursIn(candidate: String, response: String): Boolean =
        Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(candidate)}(?![\\p{L}\\p{N}])").containsMatchIn(response)

    @Serializable private data class CompletionRequest(
        val prompt: String,
        @kotlinx.serialization.SerialName("n_predict") val nPredict: Int,
        val stream: Boolean,
    )
    @Serializable private data class CompletionResponse(val content: String? = null)
    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(300)
        val json = Json { ignoreUnknownKeys = true }
        const val MOVEMENT_PROMPT = """Decide whether the player character leaves the current scene. Return exactly one final line.
Example: player waits; response says the room stays quiet.
FINAL: stay
Example: player walks through the gate; response says they arrive at the market.
FINAL: move"""
        const val LOCATION_PROMPT = """Name the destination established in the game-master response. Return exactly one final line containing the exact location name from that response.
Example response: She enters the Market Square.
FINAL: Market Square"""
    }
}
