package de.aidadventure.web.persistence

import de.aidadventure.web.model.Scenario

sealed interface ImportResult {
    data class Created(val scenario: Scenario) : ImportResult
    data class Updated(val id: String) : ImportResult
    data class Rejected(val message: String) : ImportResult
}

object ScenarioImport {
    fun fromFile(name: String, markdown: String, existing: List<Scenario>, now: Long): ImportResult {
        if (!isMarkdownFile(name)) {
            return ImportResult.Rejected("Scenario files must use the .md or .markdown extension.")
        }
        if (markdown.isBlank()) return ImportResult.Rejected("Scenario file is empty.")
        try {
            ScenarioFrontmatter.parse(markdown)
        } catch (error: IllegalArgumentException) {
            return ImportResult.Rejected(error.message ?: "Invalid scenario frontmatter.")
        }

        val fingerprint = fingerprint(name, markdown)
        val existingScenario = existing.firstOrNull { fingerprint(it.name, it.markdown) == fingerprint }
        if (existingScenario != null) return ImportResult.Updated(existingScenario.id)

        return ImportResult.Created(Scenario(fingerprint, name, markdown, now))
    }

    private fun isMarkdownFile(name: String): Boolean =
        name.lowercase().endsWith(".md") || name.lowercase().endsWith(".markdown")

    /** A stable, URL-safe FNV-1a fingerprint for a filename/content pair. */
    private fun fingerprint(name: String, markdown: String): String {
        var hash = -0x340d631b8c46753bL
        for (char in "$name\u0000$markdown") {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return "scenario-" + hash.toULong().toString(16)
    }
}
