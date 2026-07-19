package de.aidadventure.server.prefill

internal fun parseFragmentRelevance(response: String): Int? =
    response.trim().takeIf { it.length == 1 && it[0] in '0'..'9' }?.let { it[0] - '0' }
