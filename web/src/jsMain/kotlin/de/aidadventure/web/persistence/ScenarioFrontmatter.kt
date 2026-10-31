package de.aidadventure.web.persistence

import de.aidadventure.web.model.WorldState
import kotlin.js.JsModule
import kotlin.js.JsNonModule

/** One flag/value predicate or one exact current-location predicate. */
data class FlagCondition(val flag: String? = null, val value: Boolean? = null, val location: String? = null)
data class FlagAssignment(val flag: String, val value: Boolean)
data class SignalCheckDefinition(
    val name: String,
    val description: String,
    val conditions: List<FlagCondition> = emptyList(),
    val emittedTexts: List<String> = emptyList(),
    val excludedConditions: List<List<FlagCondition>> = emptyList(),
    val eventCheck: Boolean = true,
)

data class RuntimeFragmentDefinition(
    val matchText: String,
    val fragment: String,
    val conditions: List<FlagCondition> = emptyList(),
    val emittedSignals: List<String> = emptyList(),
    val direct: Boolean = false,
    val excludedConditions: List<List<FlagCondition>> = emptyList(),
)

data class LocationTriggerAction(
    val setFlags: List<FlagAssignment> = emptyList(),
    val emitText: String? = null,
    val signals: List<String> = emptyList(),
)

data class ParsedScenario(
    val promptMarkdown: String,
    val runtimeFragments: List<RuntimeFragmentDefinition>,
    val initialWorldState: WorldState,
    val locations: Map<String, String>,
    val initialSituationSetting: String = "",
    val initialFlags: Map<String, Boolean> = emptyMap(),
    val signals: Map<String, List<FlagAssignment>> = emptyMap(),
    val signalChecks: List<SignalCheckDefinition> = emptyList(),
    val locationTriggers: Map<String, List<LocationTriggerAction>> = emptyMap(),
)

object ScenarioFrontmatter {
    fun parse(markdown: String): ParsedScenario {
        val closingDelimiterStart = closingFrontmatterDelimiter(markdown)
            ?: return ParsedScenario(markdown, emptyList(), WorldState(), emptyMap())
        val frontmatter = markdown.substring(0, closingDelimiterStart)
        val rootEntries = readFrontmatter(frontmatter)
        val initialSituation = readInitialSituation(rootEntries, frontmatter)
        val initialWorldState = initialSituation.first ?: readInitialWorldState(rootEntries, frontmatter)
        val locationNames = readLocationNames(rootEntries)
        val flags = readFlags(rootEntries)
        val declaredChecks = linkedMapOf<String, Pair<String?, List<FlagCondition>>>()
        val emittedTexts = linkedMapOf<String, List<String>>()
        val signals = readSignals(rootEntries, flags.keys, locationNames, declaredChecks, emittedTexts)
        val (locations, locationTriggers) = readLocations(rootEntries, flags.keys, signals.keys)
        val fragments = readRuntimeFragments(rootEntries, flags.keys, locations.keys, signals.keys, flagsEntryPresent = rootEntries.any { it[0] == "flags" } || rootEntries.any { it[0] == "signals" })
        val checks = signals.keys.flatMap { name ->
            val (description, conditions) = declaredChecks.getValue(name)
            val directReference = fragments.any { it.direct && name in it.emittedSignals }
            if (description != null) listOf(SignalCheckDefinition(name, description, conditions))
            else {
                val metadata = if (directReference) listOf(SignalCheckDefinition(name, name, conditions, eventCheck = false)) else emptyList()
                val events = fragments.filter { !it.direct && name in it.emittedSignals }.map {
                    SignalCheckDefinition(name, it.matchText, (conditions + it.conditions).distinct(), excludedConditions = it.excludedConditions)
                }.distinct()
                metadata + events.ifEmpty { if (directReference) emptyList() else listOf(SignalCheckDefinition(name, name, conditions)) }
            }
        }
        if (locations.isNotEmpty() && initialWorldState.location.isNotBlank() && initialWorldState.location !in locations.keys) {
            invalidFrontmatter()
        }
        return ParsedScenario(
            promptMarkdown = markdown.substring(closingDelimiterStart + 3).removePrefix("\r\n").removePrefix("\n"),
            runtimeFragments = fragments,
            initialWorldState = initialWorldState,
            locations = locations,
            initialSituationSetting = initialSituation.second,
            initialFlags = flags,
            signals = signals,
            signalChecks = checks.map { it.copy(emittedTexts = emittedTexts[it.name].orEmpty()) },
            locationTriggers = locationTriggers,
        )
    }

    private fun readInitialSituation(rootEntries: Array<Array<dynamic>>, frontmatter: String): Pair<WorldState?, String> {
        val entry = rootEntries.firstOrNull { it[0] == "initial-situation" } ?: return null to ""
        val fields = mappingEntries(entry[1]) ?: invalidFrontmatter()
        if (fields.any { it[0] !in setOf("world-state", "setting") }) invalidFrontmatter()
        val worldStateField = fields.firstOrNull { it[0] == "world-state" }
        val worldState = worldStateField?.let {
            val stateFields = mappingEntries(it[1]) ?: invalidFrontmatter()
            if (stateFields.size != 1 || stateFields.single()[0] != "location") invalidFrontmatter()
            val location = stateFields.single()[1] as? String ?: invalidFrontmatter()
            if (location.isBlank()) invalidFrontmatter()
            WorldState(location)
        }
        val settingField = fields.firstOrNull { it[0] == "setting" }
        val setting = settingField?.get(1) as? String ?: if (settingField == null) "" else invalidFrontmatter()
        if (settingField != null && setting.isBlank()) invalidFrontmatter()
        return worldState to setting
    }

    private fun readFrontmatter(frontmatter: String): Array<Array<dynamic>> {
        val root = try {
            JsYaml.load(frontmatter)
        } catch (cause: Throwable) {
            throw IllegalArgumentException("Invalid scenario frontmatter: ${cause.message ?: "YAML syntax error"}", cause)
        }
        return mappingEntries(root) ?: invalidFrontmatter()
    }

    private fun readFlags(rootEntries: Array<Array<dynamic>>): Map<String, Boolean> {
        val entry = rootEntries.firstOrNull { it[0] == "flags" } ?: return emptyMap()
        val entries = mappingEntries(entry[1]) ?: invalidFrontmatter("flags", "expected a mapping of names to booleans")
        val flags = linkedMapOf<String, Boolean>()
        entries.forEach { field ->
            val name = field[0] as? String ?: invalidFrontmatter("flags", "flag names must be strings")
            if (name.isBlank()) invalidFrontmatter("flags", "flag names cannot be blank")
            val value = field[1] as? Boolean ?: invalidFrontmatter("flags.$name", "expected true or false")
            flags[name] = value
        }
        return flags
    }

    private fun readSignals(
        rootEntries: Array<Array<dynamic>>,
        declaredFlags: Set<String>,
        declaredLocations: Set<String>,
        declaredChecks: MutableMap<String, Pair<String?, List<FlagCondition>>>,
        emittedTexts: MutableMap<String, List<String>>,
    ): Map<String, List<FlagAssignment>> {
        val entry = rootEntries.firstOrNull { it[0] == "signals" } ?: return emptyMap()
        val source = entry[1]
        val declarations: List<Pair<String, dynamic>> = when {
            mappingEntries(source) != null -> mappingEntries(source)!!.map { field ->
                val name = field[0] as? String ?: invalidFrontmatter("signals", "signal names must be strings")
                name to field[1]
            }
            isArray(source) -> source.unsafeCast<Array<dynamic>>().mapIndexed { index, item ->
                val fields = mappingEntries(item) ?: invalidFrontmatter("signals[$index]", "expected one named signal")
                if (fields.size != 1) invalidFrontmatter("signals[$index]", "expected one named signal")
                val name = fields.single()[0] as? String ?: invalidFrontmatter("signals[$index]", "signal name must be a string")
                name to fields.single()[1]
            }
            else -> invalidFrontmatter("signals", "expected a mapping or list of named signals")
        }
        val signals = linkedMapOf<String, List<FlagAssignment>>()
        declarations.forEach { (name, value) ->
            val path = "signals.$name"
            if (name.isBlank()) invalidFrontmatter("signals", "signal names cannot be blank")
            if (name in signals) invalidFrontmatter(path, "signal is declared more than once")
            val fields = mappingEntries(value) ?: invalidFrontmatter(path, "expected triggers")
            if (fields.any { it[0] !in setOf("triggers", "description", "condition") }) invalidFrontmatter(path, "expected triggers, description, or condition")
            val descriptionField = fields.firstOrNull { it[0] == "description" }
            val description = descriptionField?.let {
                (it[1] as? String)?.takeIf { text -> text.isNotBlank() }
                    ?: invalidFrontmatter("$path.description", "expected nonblank text")
            }
            val conditions = fields.firstOrNull { it[0] == "condition" }?.let {
                readConditions(it[1], "$path.condition", declaredFlags, declaredLocations)
            } ?: emptyList()
            declaredChecks[name] = description to conditions
            val triggers = fields.firstOrNull { it[0] == "triggers" }?.get(1)
                ?: invalidFrontmatter("$path.triggers", "expected a list")
            if (!isArray(triggers)) invalidFrontmatter("$path.triggers", "expected a list")
            if (triggers.unsafeCast<Array<dynamic>>().isEmpty()) {
                invalidFrontmatter("$path.triggers", "expected at least one action")
            }
            val actions = mutableListOf<FlagAssignment>()
            val texts = mutableListOf<String>()
            triggers.unsafeCast<Array<dynamic>>().forEachIndexed { index, trigger ->
                val triggerPath = "$path.triggers[$index]"
                val action = mappingEntries(trigger) ?: invalidFrontmatter(triggerPath, "expected set-flag")
                if (action.size != 1 || action.single()[0] !in setOf("set-flag", "emit-text")) invalidFrontmatter(triggerPath, "expected set-flag or emit-text")
                if (action.single()[0] == "emit-text") {
                    texts += (action.single()[1] as? String)?.takeIf { it.isNotBlank() }
                        ?: invalidFrontmatter("$triggerPath.emit-text", "expected nonblank text")
                    return@forEachIndexed
                }
                val assignments = mappingEntries(action.single()[1])
                    ?: invalidFrontmatter("$triggerPath.set-flag", "expected flag assignments")
                if (assignments.isEmpty()) invalidFrontmatter("$triggerPath.set-flag", "must assign at least one flag")
                assignments.forEach { assignment ->
                    val flag = assignment[0] as? String ?: invalidFrontmatter("$triggerPath.set-flag", "flag name must be a string")
                    if (flag !in declaredFlags) invalidFrontmatter("$triggerPath.set-flag.$flag", "flag is not declared")
                    val assigned = assignment[1] as? Boolean
                        ?: invalidFrontmatter("$triggerPath.set-flag.$flag", "expected true or false")
                    actions += FlagAssignment(flag, assigned)
                }
            }
            signals[name] = actions
            emittedTexts[name] = texts
        }
        return signals
    }

    private fun readRuntimeFragments(
        rootEntries: Array<Array<dynamic>>,
        declaredFlags: Set<String>,
        declaredLocations: Set<String>,
        declaredSignals: Set<String>,
        flagsEntryPresent: Boolean,
    ): List<RuntimeFragmentDefinition> {
        val fragmentsEntry = rootEntries.firstOrNull { it[0] == "runtime-fragments" } ?: return emptyList()
        val definitions = mutableListOf<RuntimeFragmentDefinition>()

        fun visit(
            value: dynamic,
            keyPath: List<String>,
            conditions: List<FlagCondition>,
            emittedSignals: List<String>,
            emittedText: List<String>,
            excludedConditions: List<List<FlagCondition>>,
            path: String,
        ) {
            if (value is String) {
                val textValue = value as String
                if (keyPath.isEmpty() || textValue.isBlank()) invalidFrontmatter(path, "fragment text cannot be blank")
                definitions += RuntimeFragmentDefinition(keyPath.joinToString("\n"), joinEmittedText(emittedText + textValue), conditions, emittedSignals, excludedConditions = excludedConditions)
                return
            }
            val entries = mappingEntries(value) ?: invalidFrontmatter(path, "expected a mapping or text")
            if (entries.isEmpty()) {
                if (keyPath.isEmpty() && path == "runtime-fragments") return
                invalidFrontmatter(path, "empty fragment group")
            }
            // Preserve reserved-looking legacy labels when there are no flag/signal declarations.
            val metadata = flagsEntryPresent
            val ownText = entries.firstOrNull { metadata && it[0] == "emit-text" }?.let {
                (it[1] as? String)?.takeIf { text -> text.isNotBlank() }
                    ?: invalidFrontmatter("$path.emit-text", "expected nonblank text")
            }
            val ownSignal = entries.firstOrNull { metadata && it[0] == "signal" }?.let {
                val name = (it[1] as? String)?.takeIf { text -> text.isNotBlank() }
                    ?: invalidFrontmatter("$path.signal", "expected a signal name")
                if (name !in declaredSignals) invalidFrontmatter("$path.signal", "signal '$name' is not declared")
                name
            }
            val ownCondition = entries.firstOrNull { metadata && it[0] == "condition" }
            val nextConditions = conditions + if (ownCondition != null && !isArray(ownCondition[1])) {
                readConditions(ownCondition[1], "$path.condition", declaredFlags, declaredLocations)
            } else emptyList()
            val nextText = emittedText + listOfNotNull(ownText)
            val nextSignals = (emittedSignals + listOfNotNull(ownSignal)).distinct()
            val children = entries.filter { !metadata || it[0] !in setOf("emit-text", "signal", "condition") }
            val conditionalBranches = ownCondition != null && isArray(ownCondition[1])
            // Root effects are immediate independent rules. Named descendants only inherit predicates.
            if (keyPath.isEmpty() && (ownText != null || ownSignal != null)) {
                definitions += RuntimeFragmentDefinition("", joinEmittedText(nextText), nextConditions, nextSignals, direct = true, excludedConditions = excludedConditions)
            }
            val childText = if (keyPath.isEmpty()) emptyList() else nextText
            val childSignals = if (keyPath.isEmpty()) emptyList() else nextSignals
            children.forEach { child ->
                val key = child[0] as? String ?: invalidFrontmatter(path, "fragment names must be strings")
                if (key.isBlank()) invalidFrontmatter(path, "fragment names cannot be blank")
                visit(child[1], keyPath + key, nextConditions, childSignals, childText, excludedConditions, "$path.$key")
            }
            if (conditionalBranches) {
                val branches = ownCondition[1].unsafeCast<Array<dynamic>>()
                if (branches.isEmpty()) invalidFrontmatter("$path.condition", "expected at least one branch")
                val preceding = mutableListOf<List<FlagCondition>>()
                branches.forEachIndexed { index, branch ->
                    val branchPath = "$path.condition[$index]"
                    val fields = mappingEntries(branch) ?: invalidFrontmatter(branchPath, "expected conditions and then, or final else")
                    val elseField = fields.firstOrNull { it[0] == "else" }
                    if (elseField != null) {
                        if (fields.size != 1 || index != branches.lastIndex || preceding.isEmpty()) {
                            invalidFrontmatter(branchPath, "else must be the final branch after at least one condition")
                        }
                        val elseEntries = mappingEntries(elseField[1])
                            ?: invalidFrontmatter("$branchPath.else", "expected fragment mapping")
                        if (elseEntries.isEmpty()) invalidFrontmatter("$branchPath.else", "expected at least one effect or fragment")
                        visit(elseField[1], keyPath, nextConditions, childSignals, childText, excludedConditions + preceding, "$branchPath.else")
                    } else {
                        val then = fields.firstOrNull { it[0] == "then" }
                            ?: invalidFrontmatter(branchPath, "expected conditions and then")
                        val predicates = fields.filter { it[0] != "then" }
                        if (predicates.isEmpty()) invalidFrontmatter(branchPath, "expected at least one condition")
                        val branchConditions = predicates.map {
                            val flag = it[0] as? String ?: invalidFrontmatter(branchPath, "flag name must be a string")
                            readNamedCondition(flag, it[1], "$branchPath.$flag", declaredFlags, declaredLocations)
                        }
                        val thenEntries = mappingEntries(then[1]) ?: invalidFrontmatter("$branchPath.then", "expected fragment mapping")
                        if (thenEntries.isEmpty()) invalidFrontmatter("$branchPath.then", "expected at least one effect or fragment")
                        visit(then[1], keyPath, nextConditions + branchConditions, childSignals, childText, excludedConditions, "$branchPath.then")
                        preceding += branchConditions
                    }
                }
            }
            if (keyPath.isNotEmpty() && children.isEmpty() && !conditionalBranches) {
                if (ownText == null && ownSignal == null) invalidFrontmatter(path, "fragment has no text, signal, or children")
                definitions += RuntimeFragmentDefinition(keyPath.joinToString("\n"), joinEmittedText(nextText), nextConditions, nextSignals, excludedConditions = excludedConditions)
            }
            if (keyPath.isEmpty() && children.isEmpty() && !conditionalBranches && ownText == null && ownSignal == null) {
                invalidFrontmatter(path, "rule has no text, signal, or children")
            }
        }

        visit(fragmentsEntry[1], emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "runtime-fragments")
        val duplicatePath = definitions.filter { !it.direct }.groupBy { it.matchText }.entries.firstOrNull { it.value.size > 1 }
        if (duplicatePath != null) {
            invalidFrontmatter("runtime-fragments.${duplicatePath.key.replace('\n', '.')}", "conditional branches create the same retrieval path")
        }
        return definitions
    }

    private fun joinEmittedText(values: List<String>): String = values.joinToString("\n") { it.trimEnd('\n', '\r') }

    private fun readConditions(value: dynamic, path: String, declaredFlags: Set<String>, declaredLocations: Set<String>): List<FlagCondition> {
        val entries = mappingEntries(value) ?: invalidFrontmatter(path, "expected flag conditions or location")
        if (entries.isEmpty()) invalidFrontmatter(path, "expected at least one condition")
        return entries.map {
            val flag = it[0] as? String ?: invalidFrontmatter(path, "flag name must be a string")
            readNamedCondition(flag, it[1], "$path.$flag", declaredFlags, declaredLocations)
        }
    }

    private fun readNamedCondition(flag: String, value: dynamic, path: String, declaredFlags: Set<String>, declaredLocations: Set<String>): FlagCondition {
        if (flag == "location" && value !is Boolean) {
            val location = (value as? String)?.takeIf { it.isNotBlank() }
                ?: invalidFrontmatter(path, "expected a nonblank location name")
            if (declaredLocations.isNotEmpty() && location !in declaredLocations) invalidFrontmatter(path, "location is not declared")
            return FlagCondition(location = location)
        }
        if (flag !in declaredFlags) invalidFrontmatter(path, "flag is not declared")
        val expected = value as? Boolean ?: invalidFrontmatter(path, "expected true or false")
        return FlagCondition(flag, expected)
    }

    private fun readInitialWorldState(rootEntries: Array<Array<dynamic>>, frontmatter: String): WorldState {
        val worldStateEntry = rootEntries.firstOrNull { it[0] == "world-state" } ?: return WorldState()
        if (hasNonStringWorldStateEntityOrFactKey(frontmatter)) invalidFrontmatter()
        val worldStateEntries = mappingEntries(worldStateEntry[1]) ?: invalidFrontmatter()
        if (worldStateEntries.size != 1 || worldStateEntries.single()[0] != "location") invalidFrontmatter()
        val location = worldStateEntries.single()[1] as? String ?: invalidFrontmatter()
        if (location.isBlank()) invalidFrontmatter()
        return WorldState(location)
    }

    private fun readLocationNames(rootEntries: Array<Array<dynamic>>): Set<String> {
        val locationsEntry = rootEntries.firstOrNull { it[0] == "locations" } ?: return emptySet()
        val entries = mappingEntries(locationsEntry[1]) ?: invalidFrontmatter()
        return entries.map { it[0] as? String ?: invalidFrontmatter() }.toSet()
    }

    private fun readLocations(
        rootEntries: Array<Array<dynamic>>,
        declaredFlags: Set<String>,
        declaredSignals: Set<String>,
    ): Pair<Map<String, String>, Map<String, List<LocationTriggerAction>>> {
        val locationsEntry = rootEntries.firstOrNull { it[0] == "locations" } ?: return emptyMap<String, String>() to emptyMap()
        val entries = mappingEntries(locationsEntry[1]) ?: invalidFrontmatter()
        val locations = linkedMapOf<String, String>()
        val triggersByLocation = linkedMapOf<String, List<LocationTriggerAction>>()
        entries.forEach { entry ->
            val key = entry[0] as? String ?: invalidFrontmatter()
            val value = entry[1]
            val fields = mappingEntries(value)
            val description: String
            val triggerActions: List<LocationTriggerAction>
            if (fields == null) {
                description = when (value) {
                    null -> ""
                    is String -> value
                    else -> invalidFrontmatter("locations.$key", "expected text or a mapping")
                }
                triggerActions = emptyList()
            } else {
                val path = "locations.$key"
                if (fields.any { it[0] !in setOf("description", "triggers") }) invalidFrontmatter(path, "expected description or triggers")
                description = fields.firstOrNull { it[0] == "description" }?.let { field ->
                    when (val text = field[1]) {
                        null -> ""
                        is String -> text
                        else -> invalidFrontmatter("$path.description", "expected text")
                    }
                } ?: ""
                triggerActions = fields.firstOrNull { it[0] == "triggers" }?.let { field ->
                    val triggerPath = "$path.triggers"
                    val rawActions = field[1]
                    if (!isArray(rawActions)) invalidFrontmatter(triggerPath, "expected a list")
                    val actions = rawActions.unsafeCast<Array<dynamic>>()
                    if (actions.isEmpty()) invalidFrontmatter(triggerPath, "expected at least one action")
                    actions.mapIndexed { index, raw ->
                        val actionPath = "$triggerPath[$index]"
                        val action = mappingEntries(raw) ?: invalidFrontmatter(actionPath, "expected set-flag, emit-text, or signals")
                        if (action.size != 1 || action.single()[0] !in setOf("set-flag", "emit-text", "signals")) {
                            invalidFrontmatter(actionPath, "expected set-flag, emit-text, or signals")
                        }
                        when (action.single()[0]) {
                            "set-flag" -> {
                                val assignments = mappingEntries(action.single()[1]) ?: invalidFrontmatter("$actionPath.set-flag", "expected flag assignments")
                                if (assignments.isEmpty()) invalidFrontmatter("$actionPath.set-flag", "must assign at least one flag")
                                val values = assignments.map { assignment ->
                                    val flag = assignment[0] as? String ?: invalidFrontmatter("$actionPath.set-flag", "flag name must be a string")
                                    if (flag !in declaredFlags) invalidFrontmatter("$actionPath.set-flag.$flag", "flag is not declared")
                                    val assigned = assignment[1] as? Boolean ?: invalidFrontmatter("$actionPath.set-flag.$flag", "expected true or false")
                                    FlagAssignment(flag, assigned)
                                }
                                LocationTriggerAction(setFlags = values)
                            }
                            "emit-text" -> LocationTriggerAction(emitText = (action.single()[1] as? String)
                                ?.takeIf { it.isNotBlank() } ?: invalidFrontmatter("$actionPath.emit-text", "expected nonblank text"))
                            else -> {
                                val names = action.single()[1]
                                if (!isArray(names)) invalidFrontmatter("$actionPath.signals", "expected a list")
                                val signals = names.unsafeCast<Array<dynamic>>().mapIndexed { signalIndex, rawName ->
                                    val name = rawName as? String ?: invalidFrontmatter("$actionPath.signals[$signalIndex]", "expected a signal name")
                                    if (name !in declaredSignals) invalidFrontmatter("$actionPath.signals[$signalIndex]", "signal '$name' is not declared")
                                    name
                                }
                                if (signals.isEmpty()) invalidFrontmatter("$actionPath.signals", "expected at least one signal")
                                LocationTriggerAction(signals = signals)
                            }
                        }
                    }
                }.orEmpty()
            }
            if (key.isBlank() || locations.keys.any { it.equals(key, ignoreCase = true) }) invalidFrontmatter()
            locations[key] = description
            if (triggerActions.isNotEmpty()) triggersByLocation[key] = triggerActions
        }
        if (locations.isEmpty()) {
            invalidFrontmatter()
        }
        return locations to triggersByLocation
    }

    private fun closingFrontmatterDelimiter(markdown: String): Int? {
        if (!markdown.startsWith("---\n") && !markdown.startsWith("---\r\n")) return null

        var lineStart = markdown.indexOf('\n') + 1
        while (lineStart <= markdown.length) {
            val lineEnd = markdown.indexOf('\n', lineStart).let { if (it == -1) markdown.length else it }
            if (markdown.substring(lineStart, lineEnd).removeSuffix("\r") == "---") {
                return lineStart
            }
            if (lineEnd == markdown.length) return null
            lineStart = lineEnd + 1
        }
        return null
    }

    private fun mappingEntries(value: dynamic): Array<Array<dynamic>>? {
        if (value == null || js("typeof value !== 'object'") as Boolean || js("Array.isArray(value)") as Boolean) {
            return null
        }
        return js("Object.entries(value)").unsafeCast<Array<Array<dynamic>>>()
    }

    private fun isArray(value: dynamic): Boolean = js("Array.isArray(value)") as Boolean

    private fun hasNonStringWorldStateEntityOrFactKey(frontmatter: String): Boolean {
        var blockScalarIndent: Int? = null
        val path = mutableListOf<Pair<Int, String?>>()
        for (line in frontmatter.lineSequence()) {
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) line.length else it }
            if (blockScalarIndent != null) {
                if (line.isBlank() || indent > blockScalarIndent) continue
                blockScalarIndent = null
            }

            if (line.isStandaloneYamlNodePropertyOrAlias() && path.isStrictWorldStateNodePath()) return true
            val mappingEntry = blockMappingEntry(line) ?: continue
            while (path.lastOrNull()?.first?.let { indent <= it } == true) path.removeLast()
            val parentPath = path.map { it.second }
            val key = JsYaml.load(mappingEntry.first) as? String
            if (isStrictWorldStateMappingEntry(parentPath, key) &&
                (mappingEntry.first.hasNonAuditableYamlSyntax() || mappingEntry.second.hasNonAuditableYamlSyntax())
            ) {
                return true
            }
            if (parentPath.isEmpty() && key == "world-state" && mappingEntry.second.isAlias()) return true
            if (parentPath == listOf("world-state") && key in arrayOf("entities", "facts") && mappingEntry.second.isAlias()) return true
            if (parentPath == listOf("world-state", "entities") || parentPath == listOf("world-state", "facts")) {
                if (key == null) return true
            }
            if (parentPath == listOf("world-state") && key in arrayOf("entities", "facts") && hasNonStringFlowMappingKey(mappingEntry.second)) return true
            if (parentPath.isEmpty() && key == "world-state" && hasNonStringWorldStateFlowMappingKey(mappingEntry.second)) return true
            if (mappingEntry.second.isBlockScalarHeader()) blockScalarIndent = indent
            path += indent to key
        }
        return false
    }

    private fun isStrictWorldStateMappingEntry(parentPath: List<String?>, key: String?): Boolean =
        (parentPath.isEmpty() && key == "world-state") ||
            (parentPath == listOf("world-state") && key in arrayOf("entities", "facts")) ||
            parentPath == listOf("world-state", "entities") ||
            parentPath == listOf("world-state", "facts") ||
            (parentPath.size == 3 && parentPath.take(2) == listOf("world-state", "entities"))

    private fun List<Pair<Int, String?>>.isStrictWorldStateNodePath(): Boolean {
        val keys = map { it.second }
        return keys == listOf("world-state") ||
            keys == listOf("world-state", "entities") ||
            keys == listOf("world-state", "facts") ||
            (keys.size == 3 && keys.take(2) == listOf("world-state", "entities"))
    }

    private fun String.isStandaloneYamlNodePropertyOrAlias(): Boolean = trim().matches(
        Regex("[&*!][^\\s#]+(?:\\s+#.*)?"),
    )

    private fun String.hasNonAuditableYamlSyntax(): Boolean {
        var quote: Char? = null
        var startsNode = true
        var index = 0
        while (index < length) {
            when (quote) {
                '\'' -> if (this[index] == '\'') {
                    if (getOrNull(index + 1) == '\'') index++ else quote = null
                }
                '"' -> if (this[index] == '\\') {
                    index++
                } else if (this[index] == '"') {
                    quote = null
                }
                null -> when (val character = this[index]) {
                    '\'', '"' -> {
                        quote = character
                        startsNode = false
                    }
                    '#' -> return false
                    '&', '!', '*' -> if (startsNode) return true
                    '<' -> if (startsNode && startsWith("<<", index)) {
                        var delimiter = index + 2
                        while (getOrNull(delimiter)?.isWhitespace() == true) delimiter++
                        if (getOrNull(delimiter) == ':') return true
                        startsNode = false
                    } else {
                        startsNode = false
                    }
                    ':', ',', '{', '[' -> startsNode = true
                    else -> if (!character.isWhitespace()) startsNode = false
                }
            }
            index++
        }
        return false
    }

    private fun hasNonStringWorldStateFlowMappingKey(value: String): Boolean = flowMappingEntries(value)
        ?.any { (key, child) ->
            key in arrayOf("entities", "facts") && (child.isAlias() || hasNonStringFlowMappingKey(child))
        }
        ?: false

    private fun String.isAlias(): Boolean = trim().matches(Regex("\\*[^\\s#]+(?:\\s+#.*)?"))

    private fun hasNonStringFlowMappingKey(value: String): Boolean = flowMappingEntries(value)
        ?.any { (key, _) -> JsYaml.load(key) !is String }
        ?: false

    private fun flowMappingEntries(value: String): List<Pair<String, String>>? {
        val source = value.trim()
        if (!source.startsWith('{')) return null
        val entries = mutableListOf<Pair<String, String>>()
        var index = 1
        while (true) {
            index = source.skipWhitespace(index)
            if (source.getOrNull(index) == '}') return entries
            val colon = source.findFlowDelimiter(index, ':') ?: return null
            val key = source.substring(index, colon).trim()
            index = colon + 1
            val delimiter = source.findFlowDelimiter(index, ',', '}') ?: return null
            entries += key to source.substring(index, delimiter).trim()
            if (source[delimiter] == '}') return entries
            index = delimiter + 1
        }
    }

    private fun String.skipWhitespace(start: Int): Int {
        var index = start
        while (getOrNull(index)?.isWhitespace() == true) index++
        return index
    }

    private fun String.findFlowDelimiter(start: Int, vararg delimiters: Char): Int? {
        var quote: Char? = null
        var depth = 0
        for (index in start until length) {
            when (quote) {
                '\'' -> if (this[index] == '\'') {
                    if (getOrNull(index + 1) == '\'') continue else quote = null
                }
                '"' -> if (this[index] == '\\') {
                    continue
                } else if (this[index] == '"') {
                    quote = null
                }
                null -> when (this[index]) {
                    '\'', '"' -> quote = this[index]
                    '{', '[' -> depth++
                    '}', ']' -> if (depth > 0) depth-- else if (this[index] in delimiters) return index
                    else -> if (depth == 0 && this[index] in delimiters) return index
                }
            }
        }
        return null
    }

    private fun blockMappingEntry(line: String): Pair<String, String>? {
        var content = line.trimStart()
        if (content.startsWith("#") || content == "---" || content == "...") return null
        if (content.startsWith("- ")) content = content.drop(2)

        var quote: Char? = null
        var index = 0
        while (index < content.length) {
            when (quote) {
                '\'' -> if (content[index] == '\'') {
                    if (content.getOrNull(index + 1) == '\'') index++ else quote = null
                }
                '"' -> if (content[index] == '\\') {
                    index++
                } else if (content[index] == '"') {
                    quote = null
                }
                null -> when (content[index]) {
                    '\'', '"' -> quote = content[index]
                    ':' -> if (content.getOrNull(index + 1)?.isWhitespace() != false) {
                        return content.substring(0, index).trimEnd() to content.substring(index + 1)
                    }
                }
            }
            index++
        }
        return null
    }

    private fun String.isBlockScalarHeader(): Boolean = trim().matches(
        Regex("[|>](?:[+-]?[1-9]?|[1-9]?[+-]?)(?:\\s+#.*)?"),
    )

    private fun invalidFrontmatter(): Nothing = throw IllegalArgumentException("Invalid scenario frontmatter.")

    private fun invalidFrontmatter(path: String, problem: String): Nothing =
        throw IllegalArgumentException("Invalid scenario frontmatter at $path: $problem.")
}

@JsModule("js-yaml")
@JsNonModule
private external object JsYaml {
    fun load(input: String): dynamic
}
