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
        val rootEntries = readFrontmatter(markdown.substring(0, closingDelimiterStart))
        return ParsedScenario(
            promptMarkdown = markdown.substring(closingDelimiterStart + 3).removePrefix("\r\n").removePrefix("\n"),
            runtimeFragments = readRuntimeFragments(rootEntries),
            initialWorldState = readInitialWorldState(rootEntries),
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

    private fun readInitialWorldState(rootEntries: Array<Array<dynamic>>): WorldState {
        val worldStateEntry = rootEntries.firstOrNull { it[0] == "world-state" } ?: return WorldState()
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

    private fun invalidFrontmatter(): Nothing = throw IllegalArgumentException("Invalid scenario frontmatter.")
}

@JsModule("js-yaml")
@JsNonModule
private external object JsYaml {
    fun load(input: String): dynamic
}
