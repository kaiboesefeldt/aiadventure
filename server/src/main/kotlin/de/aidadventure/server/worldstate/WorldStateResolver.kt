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
import org.slf4j.LoggerFactory

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
        val prompt = """You update a game world state. Return only valid JSON. Propose at most 8 operations.
For createEntity, set type to createEntity. Derive id from name: lowercase ASCII letters and digits, words separated by one hyphen (for example, name Leuchtturm becomes id leuchtturm). Choose kind precisely: npc for a character, location for a place or building, item for a portable object, other only otherwise. Include a short established summary.
For setFact, set type to setFact and use an entityId that appears exactly in STATE. Use an existing id only when STATE makes the reference unambiguous. Create an entity for each clearly established named NPC, location, or item in the game-master response. Do not return an empty operations list when the response introduces or clearly names one. Do not invent facts not clearly established by the game-master response.
STATE: ${WorldStateReducer.snapshot(request.previousState)}
PLAYER ACTION: ${request.playerAction}
GAME-MASTER RESPONSE: ${request.gameMasterResponse}
JSON: {"baseRevision":${request.previousState.revision},"operations":[...]}
"""
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/completion"))
                .timeout(TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(CompletionRequest(prompt, jsonSchema = patchSchema(request.previousState.revision)))))
                .build(), HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) return@runInterruptible null
        val content = runCatching { json.decodeFromString<CompletionResponse>(response.body()).content.orEmpty() }.getOrNull()
            ?: return@runInterruptible null
        runCatching { json.decodeFromString<WorldStatePatch>(content) }
            .onFailure { logger.trace("operation=world_state phase=invalid_json sessionId={} candidate={}", request.sessionId, content) }
            .getOrNull()
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
        val logger = LoggerFactory.getLogger(LlamaCppWorldStateResolver::class.java)
        fun patchSchema(revision: Long): JsonObject = json.parseToJsonElement(
            """{
              "type":"object",
              "additionalProperties":false,
              "required":["baseRevision","operations"],
              "properties":{
                "baseRevision":{"enum":[$revision]},
                "operations":{"type":"array","maxItems":8,"items":{"oneOf":[
                  {"type":"object","additionalProperties":false,
                   "required":["type","id","kind","name","summary"],
                   "properties":{
                     "type":{"enum":["createEntity"]},
                     "id":{"type":"string","pattern":"^[a-z][a-z0-9-]{0,63}$"},
                     "kind":{"enum":["npc","location","item","other"]},
                     "name":{"type":"string","minLength":1,"maxLength":96},
                     "summary":{"type":"string","minLength":1,"maxLength":240}
                   }},
                  {"type":"object","additionalProperties":false,
                   "required":["type","name","entityId","value"],
                   "properties":{
                     "type":{"enum":["setFact"]},
                     "name":{"type":"string","minLength":1,"maxLength":48},
                     "entityId":{"type":"string","pattern":"^[a-z][a-z0-9-]{0,63}$"},
                     "value":{"type":"string","minLength":1,"maxLength":160}
                   }}
                ]}}
              }
            }""",
        ).jsonObject
    }
}
