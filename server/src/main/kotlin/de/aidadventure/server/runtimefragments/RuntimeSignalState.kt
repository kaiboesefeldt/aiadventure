package de.aidadventure.server.runtimefragments

import de.aidadventure.server.model.FlagCondition
import de.aidadventure.server.model.FlagAssignment
import de.aidadventure.server.model.RuntimeFragmentDefinition

fun eligibleRuntimeFragments(
    fragments: List<RuntimeFragmentDefinition>,
    flags: Map<String, Boolean>,
): List<RuntimeFragmentDefinition> = fragments.filter { fragment ->
    !fragment.direct && matchesRuntimeConditions(fragment.conditions, fragment.excludedConditions, flags)
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

/** Conditions and else exclusions always read a single immutable turn-start snapshot. */
internal fun matchesRuntimeConditions(
    conditions: List<FlagCondition>,
    excludedConditions: List<List<FlagCondition>>,
    flags: Map<String, Boolean>,
): Boolean = conditions.all { flags[it.flag] == it.value } &&
    excludedConditions.none { group -> group.all { flags[it.flag] == it.value } }

internal fun selectedDirectRules(fragments: List<RuntimeFragmentDefinition>, flags: Map<String, Boolean>) =
    fragments.filter { it.direct && matchesRuntimeConditions(it.conditions, it.excludedConditions, flags) }
