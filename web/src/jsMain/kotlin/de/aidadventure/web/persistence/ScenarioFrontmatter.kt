package de.aidadventure.web.persistence

import de.aidadventure.web.model.WorldEntity
import de.aidadventure.web.model.WorldState
import kotlin.js.JsModule
import kotlin.js.JsNonModule

data class RuntimeFragmentDefinition(val matchText: String, val fragment: String)

data class ParsedScenario(
    val promptMarkdown: String,
    val runtimeFragments: List<RuntimeFragmentDefinition>,
    val initialWorldState: WorldState = WorldState(),
)

object ScenarioFrontmatter {
    fun parse(markdown: String): ParsedScenario {
        val closingDelimiterStart = closingFrontmatterDelimiter(markdown) ?: return ParsedScenario(markdown, emptyList())
        val rootEntries = readRootEntries(markdown.substring(0, closingDelimiterStart))
        return ParsedScenario(
            promptMarkdown = markdown.substring(closingDelimiterStart + 3).removePrefix("\r\n").removePrefix("\n"),
            runtimeFragments = readRuntimeFragments(rootEntries),
            initialWorldState = readInitialWorldState(rootEntries),
        )
    }

    private fun readRootEntries(frontmatter: String): Array<Array<dynamic>> {
        val root = try {
            JsYaml.load(frontmatter)
        } catch (cause: Throwable) {
            throw IllegalArgumentException("Invalid scenario frontmatter.", cause)
        }
        return mappingEntries(root) ?: invalidFrontmatter()
    }

    private fun readRuntimeFragments(rootEntries: Array<Array<dynamic>>): List<RuntimeFragmentDefinition> {
        val fragmentsEntry = rootEntries.valueFor("runtime-fragments") ?: return emptyList()
        val fragmentEntries = mappingEntries(fragmentsEntry) ?: invalidFrontmatter()

        return fragmentEntries.map { entry ->
            val matchText = entry[0] as? String
            val definitionEntries = mappingEntries(entry[1])
            val fragment = definitionEntries?.firstOrNull { it[0] == "fragment" }?.get(1) as? String
            if (matchText.isNullOrBlank() || fragment.isNullOrBlank()) invalidFrontmatter()
            RuntimeFragmentDefinition(matchText, fragment)
        }
    }

    private fun readInitialWorldState(root: Array<Array<dynamic>>): WorldState {
        val value = root.valueFor("world-state") ?: return WorldState()
        val entries = mappingEntries(value) ?: invalidFrontmatter()
        requireOnlyKeys(entries, setOf("entities", "facts"))
        return WorldState(
            entities = readEntities(entries.valueFor("entities")),
            facts = readFacts(entries.valueFor("facts")),
        )
    }

    private fun readEntities(value: dynamic): Map<String, WorldEntity> {
        if (value == null) return emptyMap()
        return (mappingEntries(value) ?: invalidFrontmatter()).associate { entry ->
            val id = entry[0] as? String
            if (id.isNullOrBlank()) invalidFrontmatter()
            val fields = mappingEntries(entry[1]) ?: invalidFrontmatter()
            requireOnlyKeys(fields, setOf("kind", "name", "summary"))
            id to WorldEntity(
                kind = requiredText(fields, "kind"),
                name = requiredText(fields, "name"),
                summary = requiredText(fields, "summary"),
            )
        }
    }

    private fun readFacts(value: dynamic): Map<String, String> {
        if (value == null) return emptyMap()
        return (mappingEntries(value) ?: invalidFrontmatter()).associate { entry ->
            val key = entry[0] as? String
            val factValue = entry[1] as? String
            if (key.isNullOrBlank() || factValue == null) invalidFrontmatter()
            key to factValue
        }
    }

    private fun requireOnlyKeys(entries: Array<Array<dynamic>>, allowed: Set<String>) {
        if (entries.any { it[0] !is String || it[0] !in allowed }) invalidFrontmatter()
    }

    private fun requiredText(entries: Array<Array<dynamic>>, key: String): String =
        (entries.valueFor(key) as? String)?.takeIf { it.isNotBlank() } ?: invalidFrontmatter()

    private fun Array<Array<dynamic>>.valueFor(key: String): dynamic =
        firstOrNull { it[0] == key }?.get(1)

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
