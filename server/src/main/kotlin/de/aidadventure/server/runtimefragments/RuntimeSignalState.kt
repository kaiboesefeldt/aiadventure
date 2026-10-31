package de.aidadventure.server.runtimefragments

import de.aidadventure.server.model.FlagCondition
import de.aidadventure.server.model.FlagAssignment
import de.aidadventure.server.model.RuntimeFragmentDefinition

fun eligibleRuntimeFragments(
    fragments: List<RuntimeFragmentDefinition>,
    flags: Map<String, Boolean>,
    location: String = "",
): List<RuntimeFragmentDefinition> = fragments.filter { fragment ->
    !fragment.direct && matchesRuntimeConditions(fragment.conditions, fragment.excludedConditions, flags, location)
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

/** Conditions and else exclusions read the same immutable state snapshot. */
internal fun matchesRuntimeConditions(
    conditions: List<FlagCondition>,
    excludedConditions: List<List<FlagCondition>>,
    flags: Map<String, Boolean>,
    location: String = "",
): Boolean = conditions.all { it.matches(flags, location) } &&
    excludedConditions.none { group -> group.all { it.matches(flags, location) } }

private fun FlagCondition.matches(flags: Map<String, Boolean>, currentLocation: String): Boolean =
    if (location != null) flag == null && value == null && location == currentLocation
    else flag != null && value != null && flags[flag] == value

internal fun selectedDirectRules(fragments: List<RuntimeFragmentDefinition>, flags: Map<String, Boolean>, location: String = "") =
    fragments.filter { it.direct && matchesRuntimeConditions(it.conditions, it.excludedConditions, flags, location) }
