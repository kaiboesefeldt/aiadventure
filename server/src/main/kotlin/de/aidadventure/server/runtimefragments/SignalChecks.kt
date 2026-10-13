package de.aidadventure.server.runtimefragments

import de.aidadventure.server.model.*
import kotlinx.coroutines.CancellationException

data class SignalCheckOutcome(
    val flags: Map<String, Boolean>,
    val emittedSignals: List<String> = emptyList(),
    val diagnostics: List<StreamDiagnostic> = emptyList(),
)

/** Resolve eligibility once from the turn-start snapshot; never chain assignments within a turn. */
internal fun eligibleSignalChecks(request: WarmupRequest): List<SignalCheckDefinition> = request.signals.mapNotNull { (name, assignments) ->
    val effectiveAssignments = assignments.associate { it.flag to it.value }
    if (effectiveAssignments.none { (flag, value) -> request.flags[flag] != value }) return@mapNotNull null
    val explicit = request.signalChecks.filter { it.name == name }
    val predicates = if (explicit.isNotEmpty()) {
        explicit.filter { definition -> definition.conditions.all { request.flags[it.flag] == it.value } }
            .map { it.description }.distinct()
    } else {
        val references = request.runtimeFragments.filter { name in it.emittedSignals }
        if (references.isEmpty()) listOf(name) else references
            .filter { fragment -> fragment.conditions.all { request.flags[it.flag] == it.value } }
            .map { it.matchText }.distinct()
    }
    predicates.takeIf { it.isNotEmpty() }?.let {
        SignalCheckDefinition(name, if (it.size == 1) it.single() else it.joinToString("\nOR\n"))
    }
}

internal suspend fun runSignalChecks(
    request: WarmupRequest,
    check: suspend (SignalCheckDefinition) -> String,
): SignalCheckOutcome {
    val emitted = mutableListOf<String>()
    val diagnostics = mutableListOf<StreamDiagnostic>()
    for (definition in eligibleSignalChecks(request)) {
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
    val flags = applyRuntimeSignals(request.flags, request.signals, emitted)
    if (emitted.isNotEmpty()) diagnostics += StreamDiagnostic("signal", "emitted", transcriptRole = "game-master",
        fields = mapOf("emittedSignals" to emitted.joinToString(", ")) + flags.filter { (name, value) -> request.flags[name] != value }
            .mapKeys { (name, _) -> "flag:$name" }.mapValues { (_, value) -> value.toString() })
    diagnostics += StreamDiagnostic("flags", "applied", transcriptRole = "game-master", fields = mapOf(
        "emittedSignals" to emitted.joinToString(", "),
        "appliedAssignments" to emitted.flatMap { request.signals[it].orEmpty() }.joinToString(", ") { "${it.flag}=${it.value}" }))
    return SignalCheckOutcome(flags, emitted, diagnostics)
}
