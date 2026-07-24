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

fun interface WorldStateResolver { suspend fun resolve(request: WorldStateResolutionRequest): WorldStateProposal? }

class LlamaCppWorldStateResolver(
    private val port: Int,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
) : WorldStateResolver {
    override suspend fun resolve(request: WorldStateResolutionRequest): WorldStateProposal? = runInterruptible(Dispatchers.IO) {
        val prompt = """You update a game world state. Return only valid JSON. Propose at most 8 total entities and facts.
For each clearly established new NPC, location, or item in the game-master response, add an entity with its ordinary display name, kind (npc for a character, location for a place or building, item for a portable object, other only otherwise), and a short established summary. Do not derive IDs; the server does that.
For each clearly established fact, add its name, the ordinary display name of the entity it describes, and its value. A fact may reference an entity in this same proposal or an entity named in STATE. Do not invent entities or facts.
STATE: ${WorldStateReducer.snapshot(request.previousState)}
PLAYER ACTION: ${request.playerAction}
GAME-MASTER RESPONSE: ${request.gameMasterResponse}
JSON: {"baseRevision":${request.previousState.revision},"entities":[{"name":"Leuchtturm","kind":"location","summary":"Seit drei Nächten dunkel."}],"facts":[{"name":"Unruhe","entityName":"Leuchtturm","value":"true"}]}
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
        runCatching { json.decodeFromString<WorldStateProposal>(content) }
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
              "required":["baseRevision","entities","facts"],
              "properties":{
                "baseRevision":{"enum":[$revision]},
                "entities":{"type":"array","maxItems":8,"items":{"type":"object","additionalProperties":false,
                  "required":["name","kind","summary"],"properties":{
                    "name":{"type":"string","minLength":1,"maxLength":96},
                    "kind":{"enum":["npc","location","item","other"]},
                    "summary":{"type":"string","minLength":1,"maxLength":240}
                  }}},
                "facts":{"type":"array","maxItems":8,"items":{"type":"object","additionalProperties":false,
                  "required":["name","entityName","value"],"properties":{
                    "name":{"type":"string","minLength":1,"maxLength":48},
                    "entityName":{"type":"string","minLength":1,"maxLength":96},
                    "value":{"type":"string","minLength":1,"maxLength":160}
                  }}}
              }
            }""",
        ).jsonObject
    }
}
