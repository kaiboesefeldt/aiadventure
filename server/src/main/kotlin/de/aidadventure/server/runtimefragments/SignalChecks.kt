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
    val definitions = request.signalChecks.filter { it.name == name }
    val explicit = definitions.filter { it.eventCheck }
    val predicates = if (definitions.isNotEmpty()) {
        explicit.filter { definition -> matchesRuntimeConditions(definition.conditions, definition.excludedConditions, request.flags) }
            .map { it.description }.distinct()
    } else {
        val references = request.runtimeFragments.filter { !it.direct && name in it.emittedSignals }
        if (references.isEmpty()) {
            if (request.runtimeFragments.any { it.direct && name in it.emittedSignals }) emptyList() else listOf(name)
        } else references
            .filter { fragment -> matchesRuntimeConditions(fragment.conditions, fragment.excludedConditions, request.flags) }
            .map { it.matchText }.distinct()
    }
    predicates.takeIf { it.isNotEmpty() }?.let {
        SignalCheckDefinition(name, if (it.size == 1) it.single() else it.joinToString("\nOR\n"))
    }
}

internal suspend fun runSignalChecks(
    request: WarmupRequest,
    directRules: List<RuntimeFragmentDefinition> = selectedDirectRules(request.runtimeFragments, request.flags),
    check: suspend (SignalCheckDefinition) -> String,
): SignalCheckOutcome {
    val references = directRules.flatMap { it.emittedSignals }.toSet()
    val directSignals = request.signals.keys.filter { name ->
        if (name !in references) return@filter false
        val definitions = request.signalChecks.filter { it.name == name }
        val declarations = definitions.filter { !it.eventCheck }.ifEmpty { definitions }
        declarations.isEmpty() || declarations.any { matchesRuntimeConditions(it.conditions, it.excludedConditions, request.flags) }
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
    val orderedEmitted = request.signals.keys.filter { it in emitted }
    val flags = applyRuntimeSignals(request.flags, request.signals, orderedEmitted)
    if (emitted.isNotEmpty()) diagnostics += StreamDiagnostic("signal", "emitted", transcriptRole = "game-master",
        fields = mapOf("emittedSignals" to orderedEmitted.joinToString(", ")) + flags.filter { (name, value) -> request.flags[name] != value }
            .mapKeys { (name, _) -> "flag:$name" }.mapValues { (_, value) -> value.toString() })
    diagnostics += StreamDiagnostic("flags", "applied", transcriptRole = "game-master", fields = mapOf(
        "emittedSignals" to orderedEmitted.joinToString(", "),
        "appliedAssignments" to orderedEmitted.flatMap { request.signals[it].orEmpty() }.joinToString(", ") { "${it.flag}=${it.value}" }))
    // Each direct text contributes; signal actions are deduplicated by declaration name.
    val texts = directRules.mapNotNull { it.fragment.takeIf(String::isNotBlank) } + orderedEmitted.flatMap { name ->
        request.signalChecks.firstOrNull { it.name == name }?.emittedTexts.orEmpty()
    }
    return SignalCheckOutcome(flags, orderedEmitted, diagnostics, texts)
}
