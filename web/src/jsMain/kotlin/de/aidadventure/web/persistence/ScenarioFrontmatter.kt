package de.aidadventure.web.persistence

import kotlin.js.JsModule
import kotlin.js.JsNonModule

data class RuntimeFragmentDefinition(val matchText: String, val fragment: String)

data class ParsedScenario(
    val promptMarkdown: String,
    val runtimeFragments: List<RuntimeFragmentDefinition>,
)

object ScenarioFrontmatter {
    fun parse(markdown: String): ParsedScenario {
        val closingDelimiterStart = closingFrontmatterDelimiter(markdown) ?: return ParsedScenario(markdown, emptyList())
        return ParsedScenario(
            promptMarkdown = markdown.substring(closingDelimiterStart + 3).removePrefix("\r\n").removePrefix("\n"),
            runtimeFragments = readRuntimeFragments(markdown.substring(0, closingDelimiterStart)),
        )
    }

    private fun readRuntimeFragments(frontmatter: String): List<RuntimeFragmentDefinition> {
        val root = try {
            JsYaml.load(frontmatter)
        } catch (cause: Throwable) {
            throw IllegalArgumentException("Invalid scenario frontmatter.", cause)
        }
        val rootEntries = mappingEntries(root) ?: invalidFrontmatter()
        val fragments = rootEntries.firstOrNull { it[0] == "runtime-fragments" }?.get(1) ?: invalidFrontmatter()
        val fragmentEntries = mappingEntries(fragments) ?: invalidFrontmatter()

        return fragmentEntries.map { entry ->
            val matchText = entry[0] as? String
            val definitionEntries = mappingEntries(entry[1])
            val fragment = definitionEntries?.firstOrNull { it[0] == "fragment" }?.get(1) as? String
            if (matchText.isNullOrBlank() || fragment.isNullOrBlank()) invalidFrontmatter()
            RuntimeFragmentDefinition(matchText, fragment)
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
