package de.aidadventure.server.worldstate

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

data class WorldStateResolutionRequest(
    val sessionId: String,
    val scenarioMarkdown: String,
    val previousState: WorldState,
    val playerAction: String,
    val gameMasterResponse: String,
    val locations: List<String> = emptyList(),
)

internal object WorldStateAnalysis {
    private val resultJson = Json

    const val LOCATION_PROMPT = "Select the one canonical location where the player character actually is after the preceding player action and game-master response. Do not select a place that is only planned, mentioned, inaccessible, or refused."
    const val FOCUS_PROMPT = "Reply with a short sentence describing the current narative focus after the player and game-master actions. Ignore future actions or options."

    val narrativeFocusSchema = schema("""{"type":"object","additionalProperties":false,"required":["narrativeFocus"],"properties":{"narrativeFocus":{"anyOf":[{"const":""},{"type":"string","minLength":3,"maxLength":160}]}}}""")

    fun apply(
        request: WorldStateResolutionRequest,
        locationContent: String?,
        narrativeFocusContent: String?,
    ): WorldState? {
        val location = locationContent?.let(::location)
            ?.takeIf { it in request.locations && occursIn(it, request.gameMasterResponse) }
        val narrativeFocus = narrativeFocusContent?.let(::narrativeFocus) ?: request.previousState.narrativeFocus
        return request.previousState.copy(
            location = location ?: request.previousState.location,
            narrativeFocus = narrativeFocus,
        ).takeIf { it != request.previousState }
    }

    fun locationSchema(locations: List<String>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("additionalProperties", JsonPrimitive(false))
        put("required", buildJsonArray { add(JsonPrimitive("location")) })
        put("properties", buildJsonObject {
            put("location", buildJsonObject { put("enum", buildJsonArray { locations.forEach { add(JsonPrimitive(it)) } }) })
        })
    }

    private fun location(content: String): String? = runCatching { resultJson.decodeFromString<LocationResult>(content).location }
        .getOrNull()
        ?.trim()
        ?.trimEnd(',', ';', ':', '.', '!', '?')
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun narrativeFocus(content: String): String? = runCatching { resultJson.decodeFromString<NarrativeFocusResult>(content).narrativeFocus }
        .getOrNull()
        ?.trim()
        ?.takeUnless { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun occursIn(candidate: String, response: String): Boolean =
        Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(candidate)}(?![\\p{L}\\p{N}])").containsMatchIn(response)

    @Serializable private data class LocationResult(val location: String)
    @Serializable private data class NarrativeFocusResult(val narrativeFocus: String)

    private fun schema(source: String): JsonObject = resultJson.parseToJsonElement(source).jsonObject
}
