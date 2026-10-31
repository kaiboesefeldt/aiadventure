package de.aidadventure.server.runtimefragments

import de.aidadventure.server.model.*
import kotlinx.coroutines.CancellationException

data class SignalCheckOutcome(
    val flags: Map<String, Boolean>,
    val emittedSignals: List<String> = emptyList(),
    val diagnostics: List<StreamDiagnostic> = emptyList(),
    val emittedTexts: List<String> = emptyList(),
    val nextRuntimeContext: String = "",
)

/** Resolve eligibility once from the turn-start snapshot; never chain assignments within a turn. */
internal fun eligibleSignalChecks(request: WarmupRequest): List<SignalCheckDefinition> = request.signals.mapNotNull { (name, assignments) ->
    val effectiveAssignments = assignments.associate { it.flag to it.value }
    if (request.signalChecks.none { it.name == name && it.emittedTexts.isNotEmpty() } && effectiveAssignments.none { (flag, value) -> request.flags[flag] != value }) return@mapNotNull null
    val predicates = request.signalChecks.filter { definition ->
        definition.name == name && !definition.eventCheck.isNullOrBlank() &&
            matchesRuntimeConditions(definition.conditions, definition.excludedConditions, request.flags, request.worldState.location)
    }.mapNotNull { it.eventCheck }.distinct()
    predicates.takeIf { it.isNotEmpty() }?.let {
        SignalCheckDefinition(name, if (it.size == 1) it.single() else it.joinToString("\nOR\n"))
    }
}

internal suspend fun runSignalChecks(
    request: WarmupRequest,
    directRules: List<RuntimeFragmentDefinition> = selectedDirectRules(request.runtimeFragments, request.flags, request.worldState.location),
    selectedFragment: RuntimeFragmentDefinition? = null,
    check: suspend (SignalCheckDefinition) -> String,
): SignalCheckOutcome {
    val acceptedFragment = selectedFragment?.takeIf {
        matchesRuntimeConditions(it.conditions, it.excludedConditions, request.flags, request.worldState.location)
    }
    val references = (directRules.flatMap { it.emittedSignals } + acceptedFragment?.emittedSignals.orEmpty()).toSet()
    val locationActions = request.enteredLocations.flatMap { request.locationTriggers[it].orEmpty() }
    val locationSignalReferences = locationActions.flatMap { it.signals }.distinct()
    val directSignals = request.signals.keys.filter { name ->
        if (name !in references && name !in locationSignalReferences) return@filter false
        val definitions = request.signalChecks.filter { it.name == name }
        definitions.isEmpty() || definitions.any { matchesRuntimeConditions(it.conditions, it.excludedConditions, request.flags, request.worldState.location) }
    }.toSet()
    val emitted = directSignals.toMutableList()
    val diagnostics = mutableListOf<StreamDiagnostic>()
    for (definition in eligibleSignalChecks(request).filter { it.name !in directSignals }) {
        try {
            val answer = check(definition)
            if (answer == "Y") emitted += definition.name
            diagnostics += StreamDiagnostic("signal_check", "completed", transcriptRole = "game-master",
                fields = mapOf("signal" to definition.name, "result" to if (answer == "Y") "triggered" else if (answer == "N") "not_triggered" else "invalid"))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            diagnostics += StreamDiagnostic("signal_check", "failed", transcriptRole = "game-master",
                fields = mapOf("signal" to definition.name, "failureCategory" to failure.javaClass.simpleName))
        }
    }
    val orderedEmitted = (locationSignalReferences.filter { it in emitted } + request.signals.keys.filter { it in emitted }).distinct()
    val workingFlags = request.flags.toMutableMap()
    val texts = mutableListOf<String>()
    val appliedLocationSignals = mutableSetOf<String>()

    fun applySignal(name: String, detection: String) {
        diagnostics += StreamDiagnostic("signal_detected", "detected", transcriptRole = "game-master",
            fields = mapOf("signal" to name, "detection" to detection))
        request.signals[name].orEmpty().forEach { assignment ->
            val previous = workingFlags[assignment.flag]?.toString() ?: "unset"
            workingFlags[assignment.flag] = assignment.value
            diagnostics += StreamDiagnostic("flag_set", "applied", transcriptRole = "game-master",
                fields = mapOf("flag" to assignment.flag, "previousValue" to previous, "value" to assignment.value.toString(), "signal" to name))
        }
        request.signalChecks.firstOrNull { it.name == name }?.emittedTexts.orEmpty().filter(String::isNotBlank).forEach { text ->
            diagnostics += StreamDiagnostic("text_emitted", "emitted", transcriptRole = "game-master",
                fields = mapOf("text" to text, "sourceType" to "signal", "sourceName" to name))
            texts += text
        }
    }

    // Entry actions run in declaration order. Signal eligibility still uses request.flags,
    // so assignments from one action cannot make another signal newly eligible this turn.
    locationActions.forEach { action ->
        action.setFlags.forEach { assignment ->
            val previous = workingFlags[assignment.flag]?.toString() ?: "unset"
            workingFlags[assignment.flag] = assignment.value
            diagnostics += StreamDiagnostic("flag_set", "applied", transcriptRole = "game-master",
                fields = mapOf("flag" to assignment.flag, "previousValue" to previous, "value" to assignment.value.toString(), "sourceType" to "location"))
        }
        action.emitText?.let { text ->
            diagnostics += StreamDiagnostic("text_emitted", "emitted", transcriptRole = "game-master",
                fields = mapOf("text" to text, "sourceType" to "location"))
            texts += text
        }
        action.signals.distinct().forEach { name ->
            if (name in directSignals && appliedLocationSignals.add(name)) applySignal(name, "location")
        }
    }

    directRules.forEachIndexed { index, rule ->
        if (rule.fragment.isNotBlank()) diagnostics += StreamDiagnostic("text_emitted", "emitted", transcriptRole = "game-master",
            fields = mapOf("text" to rule.fragment, "sourceType" to "flag_rule", "sourceName" to directRuleName(rule, index))).also { texts += rule.fragment }
    }
    orderedEmitted.forEach { name ->
        if (name !in appliedLocationSignals) applySignal(name, if (name in directSignals) "direct" else "model")
    }
    val flags = workingFlags.toMap()
    if (emitted.isNotEmpty()) diagnostics += StreamDiagnostic("signal", "emitted", transcriptRole = "game-master",
        fields = mapOf("emittedSignals" to orderedEmitted.joinToString(", ")) + flags.filter { (name, value) -> request.flags[name] != value }
            .mapKeys { (name, _) -> "flag:$name" }.mapValues { (_, value) -> value.toString() })
    diagnostics += StreamDiagnostic("flags", "applied", transcriptRole = "game-master", fields = mapOf(
        "emittedSignals" to orderedEmitted.joinToString(", "),
        "appliedAssignments" to orderedEmitted.flatMap { request.signals[it].orEmpty() }.joinToString(", ") { "${it.flag}=${it.value}" }))
    return SignalCheckOutcome(flags, orderedEmitted, diagnostics, texts)
}

private fun directRuleName(rule: RuntimeFragmentDefinition, index: Int): String {
    if (rule.matchText.isNotBlank()) return rule.matchText
    val predicates = rule.conditions.map { it.displayPredicate() } + rule.excludedConditions.map { excluded ->
        "not (${excluded.joinToString(" and ") { it.displayPredicate() }})"
    }
    return predicates.joinToString(" and ").ifBlank { "Root rule ${index + 1}" }
}

private fun FlagCondition.displayPredicate(): String = location?.let { "location=$it" } ?: "$flag=$value"
