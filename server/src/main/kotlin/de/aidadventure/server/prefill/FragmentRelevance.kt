package de.aidadventure.server.prefill

internal fun parseFragmentDecision(response: String): Boolean? = when (response) {
    "Y" -> true
    "N" -> false
    else -> null
}
