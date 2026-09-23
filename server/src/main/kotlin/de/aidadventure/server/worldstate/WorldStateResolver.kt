package de.aidadventure.server.worldstate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WorldStateResolutionRequest(
    val sessionId: String,
    val scenarioMarkdown: String,
    val previousState: WorldState,
    val playerAction: String,
    val gameMasterResponse: String,
    val locations: Map<String, String> = emptyMap(),
)

internal object WorldStateAnalysis {
    private val resultJson = Json

    const val PROMPT = "Update the current world state from the preceding player action and game-master response. Return the current narrative focus as a short sentence; ignore future actions or options. When a location catalog is provided, return exactly one key for where the player character actually is. Use descriptions to distinguish places, but do not select a place that is only planned, mentioned, inaccessible, or refused."

    fun apply(
        request: WorldStateResolutionRequest,
        content: String?,
    ): WorldState? {
        val fields = content?.let(::fields)
        val location = fields?.get("location")?.let(::location)
            ?.takeIf { it in request.locations.keys }
        val narrativeFocus = fields?.get("narrativeFocus")?.let(::narrativeFocus) ?: request.previousState.narrativeFocus
        return request.previousState.copy(
            location = location ?: request.previousState.location,
            narrativeFocus = narrativeFocus,
        ).takeIf { it != request.previousState }
    }

    fun combinedSchema(locations: Map<String, String>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("additionalProperties", JsonPrimitive(false))
        put("required", buildJsonArray {
            if (locations.isNotEmpty()) add(JsonPrimitive("location"))
            add(JsonPrimitive("narrativeFocus"))
        })
        put("properties", buildJsonObject {
            if (locations.isNotEmpty()) put("location", buildJsonObject {
                put("enum", buildJsonArray {
                    locations.keys.sortedWith(compareBy<String> { it.lowercase() }.thenBy { it }).forEach { add(JsonPrimitive(it)) }
                })
            })
            put("narrativeFocus", buildJsonObject {
                put("anyOf", buildJsonArray {
                    add(buildJsonObject { put("const", JsonPrimitive("")) })
                    add(buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("minLength", JsonPrimitive(3))
                        put("maxLength", JsonPrimitive(400))
                    })
                })
            })
        })
    }

    private fun location(content: String): String? = content
        .trim()
        .trimEnd(',', ';', ':', '.', '!', '?')
        .trim()
        .takeIf(String::isNotBlank)

    private fun narrativeFocus(content: String): String? = content
        .trim()
        .takeUnless { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun fields(content: String): Map<String, String> {
        val jsonObject = runCatching { resultJson.parseToJsonElement(content).jsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            jsonObject.forEach { (key, value) ->
                val primitive = runCatching { value.jsonPrimitive }.getOrNull()
                if (primitive?.isString == true) put(key, primitive.content)
            }
        }
    }
}
