package de.aidadventure.web.persistence

import kotlin.test.Test
import kotlin.test.assertEquals

class ScenarioFrontmatterTest {
    @Test
    fun parsesFragmentsAndStripsFrontmatter() {
        val parsed = ScenarioFrontmatter.parse(
            "---\nruntime-fragments:\n  lighthouse:\n    fragment: Mira remembers it\n---\n# Harbor",
        )

        assertEquals("# Harbor", parsed.promptMarkdown)
        assertEquals(
            listOf(RuntimeFragmentDefinition("lighthouse", "Mira remembers it")),
            parsed.runtimeFragments,
        )
    }

    @Test
    fun templateWithoutFrontmatterKeepsFullMarkdownAndHasNoFragments() {
        val parsed = ScenarioFrontmatter.parse("# Harbor")

        assertEquals("# Harbor", parsed.promptMarkdown)
        assertEquals(emptyList(), parsed.runtimeFragments)
    }

    @Test
    fun frontmatterWithoutRuntimeFragmentsIsStrippedAndHasNoFragments() {
        val parsed = ScenarioFrontmatter.parse("---\ntitle: Harbor\n---\n# Harbor")

        assertEquals("# Harbor", parsed.promptMarkdown)
        assertEquals(emptyList(), parsed.runtimeFragments)
    }
}
