package de.aidadventure.server.runtimefragments

import de.aidadventure.server.model.FlagAssignment
import de.aidadventure.server.model.RuntimeFragmentDefinition

fun eligibleRuntimeFragments(
    fragments: List<RuntimeFragmentDefinition>,
    flags: Map<String, Boolean>,
): List<RuntimeFragmentDefinition> = fragments.filter { fragment ->
    fragment.conditions.all { condition -> flags[condition.flag] == condition.value }
}

fun applyRuntimeSignals(
    flags: Map<String, Boolean>,
    signals: Map<String, List<FlagAssignment>>,
    emittedSignals: List<String>,
): Map<String, Boolean> = flags.toMutableMap().apply {
    emittedSignals.forEach { name ->
        signals[name].orEmpty().forEach { assignment -> put(assignment.flag, assignment.value) }
    }
}
