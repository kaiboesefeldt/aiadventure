package de.aidadventure.web.persistence

import de.aidadventure.web.model.WorldState
import kotlin.js.JsModule
import kotlin.js.JsNonModule

data class RuntimeFragmentDefinition(val matchText: String, val fragment: String)

data class ParsedScenario(
    val promptMarkdown: String,
    val runtimeFragments: List<RuntimeFragmentDefinition>,
    val initialWorldState: WorldState,
    val locations: Map<String, String>,
    val initialSituationSetting: String = "",
)

object ScenarioFrontmatter {
    fun parse(markdown: String): ParsedScenario {
        val closingDelimiterStart = closingFrontmatterDelimiter(markdown)
            ?: return ParsedScenario(markdown, emptyList(), WorldState(), emptyMap())
        val frontmatter = markdown.substring(0, closingDelimiterStart)
        val rootEntries = readFrontmatter(frontmatter)
        val initialSituation = readInitialSituation(rootEntries, frontmatter)
        val initialWorldState = initialSituation.first ?: readInitialWorldState(rootEntries, frontmatter)
        val locations = readLocations(rootEntries)
        if (locations.isNotEmpty() && initialWorldState.location.isNotBlank() && initialWorldState.location !in locations.keys) {
            invalidFrontmatter()
        }
        return ParsedScenario(
            promptMarkdown = markdown.substring(closingDelimiterStart + 3).removePrefix("\r\n").removePrefix("\n"),
            runtimeFragments = readRuntimeFragments(rootEntries),
            initialWorldState = initialWorldState,
            locations = locations,
            initialSituationSetting = initialSituation.second,
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
            throw IllegalArgumentException("Invalid scenario frontmatter.", cause)
        }
        return mappingEntries(root) ?: invalidFrontmatter()
    }

    private fun readRuntimeFragments(rootEntries: Array<Array<dynamic>>): List<RuntimeFragmentDefinition> {
        val fragmentsEntry = rootEntries.firstOrNull { it[0] == "runtime-fragments" } ?: return emptyList()
        val definitions = mutableListOf<RuntimeFragmentDefinition>()

        fun visit(value: dynamic, keyPath: List<String>) {
            when {
                value is String -> {
                    if (keyPath.isEmpty() || keyPath.any { it.isBlank() } || value.isBlank()) invalidFrontmatter()
                    definitions += RuntimeFragmentDefinition(keyPath.joinToString("\n"), value)
                }
                else -> {
                    val entries = mappingEntries(value) ?: invalidFrontmatter()
                    if (entries.isEmpty()) {
                        if (keyPath.isEmpty()) return
                        invalidFrontmatter()
                    }
                    entries.forEach { entry ->
                        val key = entry[0] as? String ?: invalidFrontmatter()
                        if (key.isBlank()) invalidFrontmatter()
                        visit(entry[1], keyPath + key)
                    }
                }
            }
        }

        visit(fragmentsEntry[1], emptyList())
        return definitions
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

    private fun readLocations(rootEntries: Array<Array<dynamic>>): Map<String, String> {
        val locationsEntry = rootEntries.firstOrNull { it[0] == "locations" } ?: return emptyMap()
        val entries = mappingEntries(locationsEntry[1]) ?: invalidFrontmatter()
        val locations = linkedMapOf<String, String>()
        entries.forEach { entry ->
            val key = entry[0] as? String ?: invalidFrontmatter()
            val description = when (val value = entry[1]) {
                null -> ""
                is String -> value
                else -> invalidFrontmatter()
            }
            if (key.isBlank() || locations.keys.any { it.equals(key, ignoreCase = true) }) invalidFrontmatter()
            locations[key] = description
        }
        if (locations.isEmpty()) {
            invalidFrontmatter()
        }
        return locations
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
}

@JsModule("js-yaml")
@JsNonModule
private external object JsYaml {
    fun load(input: String): dynamic
}
