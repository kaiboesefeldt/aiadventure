package de.aidadventure.web.persistence

import de.aidadventure.web.model.WorldEntity
import de.aidadventure.web.model.WorldState
import kotlin.js.JsModule
import kotlin.js.JsNonModule

data class RuntimeFragmentDefinition(val matchText: String, val fragment: String)

data class ParsedScenario(
    val promptMarkdown: String,
    val runtimeFragments: List<RuntimeFragmentDefinition>,
    val initialWorldState: WorldState,
)

object ScenarioFrontmatter {
    fun parse(markdown: String): ParsedScenario {
        val closingDelimiterStart = closingFrontmatterDelimiter(markdown)
            ?: return ParsedScenario(markdown, emptyList(), WorldState())
        val frontmatter = markdown.substring(0, closingDelimiterStart)
        val rootEntries = readFrontmatter(frontmatter)
        return ParsedScenario(
            promptMarkdown = markdown.substring(closingDelimiterStart + 3).removePrefix("\r\n").removePrefix("\n"),
            runtimeFragments = readRuntimeFragments(rootEntries),
            initialWorldState = readInitialWorldState(rootEntries, frontmatter),
        )
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
        val fragments = fragmentsEntry[1]
        val fragmentEntries = mappingEntries(fragments) ?: invalidFrontmatter()

        return fragmentEntries.map { entry ->
            val matchText = entry[0] as? String
            val definitionEntries = mappingEntries(entry[1])
            val fragment = definitionEntries?.firstOrNull { it[0] == "fragment" }?.get(1) as? String
            if (matchText.isNullOrBlank() || fragment.isNullOrBlank()) invalidFrontmatter()
            RuntimeFragmentDefinition(matchText, fragment)
        }
    }

    private fun readInitialWorldState(rootEntries: Array<Array<dynamic>>, frontmatter: String): WorldState {
        val worldStateEntry = rootEntries.firstOrNull { it[0] == "world-state" } ?: return WorldState()
        if (hasNonStringWorldStateEntityOrFactKey(frontmatter)) invalidFrontmatter()
        val worldStateEntries = mappingEntries(worldStateEntry[1]) ?: invalidFrontmatter()
        if (worldStateEntries.any { entry -> entry[0] !in arrayOf("entities", "facts") }) invalidFrontmatter()

        val entities = worldStateEntries.firstOrNull { it[0] == "entities" }
            ?.let { readEntities(it[1]) }
            ?: emptyMap()
        val facts = worldStateEntries.firstOrNull { it[0] == "facts" }
            ?.let { readFacts(it[1]) }
            ?: emptyMap()
        return WorldState(entities = entities, facts = facts)
    }

    private fun readEntities(value: dynamic): Map<String, WorldEntity> {
        val entries = mappingEntries(value) ?: invalidFrontmatter()
        return entries.associate { entry ->
            val id = entry[0] as? String
            if (id.isNullOrBlank()) invalidFrontmatter()
            id to readEntity(entry[1])
        }
    }

    private fun readEntity(value: dynamic): WorldEntity {
        val entries = mappingEntries(value) ?: invalidFrontmatter()
        if (entries.size != 3 || entries.any { entry -> entry[0] !in arrayOf("kind", "name", "summary") }) invalidFrontmatter()

        fun requiredString(key: String): String = entries.firstOrNull { it[0] == key }?.get(1) as? String
            ?: invalidFrontmatter()

        val kind = requiredString("kind")
        val name = requiredString("name")
        val summary = requiredString("summary")
        if (kind.isBlank() || name.isBlank() || summary.isBlank()) invalidFrontmatter()
        return WorldEntity(kind, name, summary)
    }

    private fun readFacts(value: dynamic): Map<String, String> {
        val entries = mappingEntries(value) ?: invalidFrontmatter()
        return entries.associate { entry ->
            val key = entry[0] as? String
            val fact = entry[1] as? String
            if (key.isNullOrBlank() || fact == null) invalidFrontmatter()
            key to fact
        }
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

            val mappingEntry = blockMappingEntry(line) ?: continue
            while (path.lastOrNull()?.first?.let { indent <= it } == true) path.removeLast()
            val parentPath = path.map { it.second }
            val key = JsYaml.load(mappingEntry.first) as? String
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

    private fun hasNonStringWorldStateFlowMappingKey(value: String): Boolean = flowMappingEntries(value)
        ?.any { (key, child) ->
            key in arrayOf("entities", "facts") && hasNonStringFlowMappingKey(child)
        }
        ?: false

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
