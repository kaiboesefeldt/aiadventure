package de.aidadventure.server.worldstate

import kotlinx.serialization.Serializable
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

internal object WorldStateAnalysis {
    private val resultJson = Json

    const val MOVEMENT_PROMPT = "Decide whether the player character leaves the current scene."
    const val LOCATION_PROMPT = "Name the current scene location established in the game-master response. Return exactly one short location name; never list alternatives, scores, explanations, or punctuation."
    const val FOCUS_PROMPT = "Reply with a short sentence describing the current narative focus after the player and game-master actions. Ignore future actions or options."

    val movementSchema = schema("""{"type":"object","additionalProperties":false,"required":["movement"],"properties":{"movement":{"enum":["stay","move"]}}}""")
    val locationSchema = schema("""{"type":"object","additionalProperties":false,"required":["location"],"properties":{"location":{"type":"string","minLength":1,"maxLength":48}}}""")
    val narrativeFocusSchema = schema("""{"type":"object","additionalProperties":false,"required":["narrativeFocus"],"properties":{"narrativeFocus":{"anyOf":[{"const":""},{"type":"string","minLength":3,"maxLength":160}]}}}""")

    fun apply(
        request: WorldStateResolutionRequest,
        movementContent: String?,
        locationContent: String?,
        narrativeFocusContent: String?,
    ): WorldState? {
        val movement = movementContent?.let { runCatching { resultJson.decodeFromString<MovementResult>(it).movement }.getOrNull() }
        val location = locationContent?.let(::location)
            ?.takeIf { it.length in 1..96 && occursIn(it, request.gameMasterResponse) }
        val narrativeFocus = narrativeFocusContent?.let(::narrativeFocus) ?: request.previousState.narrativeFocus
        val resolvedLocation = if (request.previousState.location.isBlank() || movement == "move") location else null
        return request.previousState.copy(
            location = resolvedLocation ?: request.previousState.location,
            narrativeFocus = narrativeFocus,
        ).takeIf { it != request.previousState }
    }

    fun isMove(content: String?): Boolean = content?.let {
        runCatching { resultJson.decodeFromString<MovementResult>(it).movement == "move" }.getOrDefault(false)
    } ?: false

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

    @Serializable private data class MovementResult(val movement: String)
    @Serializable private data class LocationResult(val location: String)
    @Serializable private data class NarrativeFocusResult(val narrativeFocus: String)

    private fun schema(source: String): JsonObject = resultJson.parseToJsonElement(source).jsonObject
}
