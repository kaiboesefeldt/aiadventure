package de.aidadventure.server.worldstate

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class WorldStateResolutionRequest(
    val sessionId: String,
    val scenarioMarkdown: String,
    val previousState: WorldState,
    val playerAction: String,
    val gameMasterResponse: String,
)

fun interface WorldStateResolver { suspend fun resolve(request: WorldStateResolutionRequest): WorldStatePatch? }

class LlamaCppWorldStateResolver(
    private val port: Int,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
) : WorldStateResolver {
    override suspend fun resolve(request: WorldStateResolutionRequest): WorldStatePatch? = runInterruptible(Dispatchers.IO) {
        val prompt = """You update a game world state. Return only valid JSON. Propose at most 8 operations. Use an existing id only when the supplied state makes the reference unambiguous. Otherwise make no operation. New entities require createEntity. Do not invent facts not clearly established by the game-master response.
STATE: ${WorldStateReducer.snapshot(request.previousState)}
PLAYER ACTION: ${request.playerAction}
GAME-MASTER RESPONSE: ${request.gameMasterResponse}
JSON: {"baseRevision":${request.previousState.revision},"operations":[...]}
"""
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/completion"))
                .timeout(TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(CompletionRequest(prompt, jsonSchema = PATCH_SCHEMA))))
                .build(), HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) return@runInterruptible null
        runCatching { json.decodeFromString<CompletionResponse>(response.body()).content.orEmpty() }
            .mapCatching { json.decodeFromString<WorldStatePatch>(it) }.getOrNull()
    }

    @kotlinx.serialization.Serializable private data class CompletionRequest(
        val prompt: String,
        val n_predict: Int = 512,
        val stream: Boolean = false,
        @kotlinx.serialization.SerialName("json_schema") val jsonSchema: JsonObject,
    )
    @kotlinx.serialization.Serializable private data class CompletionResponse(val content: String? = null)
    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(300)
        val json = Json { ignoreUnknownKeys = true }
        val PATCH_SCHEMA = json.parseToJsonElement(
            """{
              "type":"object",
              "additionalProperties":false,
              "required":["baseRevision","operations"],
              "properties":{
                "baseRevision":{"type":"integer","minimum":0},
                "operations":{"type":"array","maxItems":8,"items":{"type":"object"}}
              }
            }""",
        ).jsonObject
    }
}
